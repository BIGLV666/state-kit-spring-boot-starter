package io.github.biglv666.statekit.core;

import io.github.biglv666.statekit.ConflictStrategy;
import io.github.biglv666.statekit.FireArg;
import io.github.biglv666.statekit.FireOptions;
import io.github.biglv666.statekit.StateAction;
import io.github.biglv666.statekit.StateGuard;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.StateTx;
import io.github.biglv666.statekit.context.OperatorResolver;
import io.github.biglv666.statekit.define.RetryPolicy;
import io.github.biglv666.statekit.define.TransitionSpec;
import io.github.biglv666.statekit.event.StateTransitedEvent;
import io.github.biglv666.statekit.exception.EntityNotFoundException;
import io.github.biglv666.statekit.exception.GuardRejectedException;
import io.github.biglv666.statekit.exception.IllegalTransitionException;
import io.github.biglv666.statekit.exception.StateConflictException;
import io.github.biglv666.statekit.history.HistoryRecorder;
import io.github.biglv666.statekit.history.HistoryRecord;
import io.github.biglv666.statekit.store.StateAndVersion;
import io.github.biglv666.statekit.store.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link StateMachine} 的默认实现，封装 fire 主流程：
 *
 * <ol>
 *     <li>拆分 FireArg 令牌（param → 上下文，set → SET 子句列）；</li>
 *     <li>读当前态 → 路由查边；</li>
 *     <li>守卫校验（false 即拒绝）；</li>
 *     <li>CAS 更新（含 set 列），未命中按冲突策略处置；</li>
 *     <li>执行动作（同事务，异常整体回滚）；</li>
 *     <li>发布 {@link StateTransitedEvent}；</li>
 *     <li>写历史（开启 history 时，同事务）。</li>
 * </ol>
 *
 * <p>第 2~7 步整体包在 {@link TransactionTemplate}（REQUIRED 传播）里：
 * 调用方已有事务则加入，没有则自开。</p>
 *
 * <p>动作 / 守卫 bean 在首次使用时按名解析并缓存（启动期已校验存在性与类型）。</p>
 *
 * @param <S>  状态枚举类型
 * @param <ID> 实体主键类型
 */
public class DefaultStateMachine<S extends Enum<S>, ID> implements StateMachine<S, ID> {

    private static final Logger log = LoggerFactory.getLogger(DefaultStateMachine.class);

    private final MachineRuntime<S> runtime;
    private final StateStore stateStore;
    private final TransactionTemplate transactionTemplate;
    private final OperatorResolver operatorResolver;
    private final io.github.biglv666.statekit.context.TraceIdResolver traceIdResolver;
    private final HistoryRecorder historyRecorder;
    private final ApplicationEventPublisher eventPublisher;
    private final ConfigurableListableBeanFactory beanFactory;

    public DefaultStateMachine(MachineRuntime<S> runtime, StateStore stateStore,
                               TransactionTemplate transactionTemplate,
                               OperatorResolver operatorResolver,
                               io.github.biglv666.statekit.context.TraceIdResolver traceIdResolver,
                               HistoryRecorder historyRecorder,
                               ApplicationEventPublisher eventPublisher,
                               ConfigurableListableBeanFactory beanFactory) {
        this(runtime, stateStore, transactionTemplate, operatorResolver, traceIdResolver,
                historyRecorder, eventPublisher, beanFactory, null);
    }

    /** 0.2.0+：嵌套子机器机器需传入聚合器（父机器回发用） */
    public DefaultStateMachine(MachineRuntime<S> runtime, StateStore stateStore,
                               TransactionTemplate transactionTemplate,
                               OperatorResolver operatorResolver,
                               io.github.biglv666.statekit.context.TraceIdResolver traceIdResolver,
                               HistoryRecorder historyRecorder,
                               ApplicationEventPublisher eventPublisher,
                               ConfigurableListableBeanFactory beanFactory,
                               io.github.biglv666.statekit.nested.SubMachineAggregator aggregator) {
        this.runtime = runtime;
        this.stateStore = stateStore;
        this.transactionTemplate = transactionTemplate;
        this.operatorResolver = operatorResolver;
        this.traceIdResolver = traceIdResolver;
        this.historyRecorder = historyRecorder;
        this.eventPublisher = eventPublisher;
        this.beanFactory = beanFactory;
        this.aggregator = aggregator;
    }

    private final io.github.biglv666.statekit.nested.SubMachineAggregator aggregator;

    @Override
    public void fire(ID id, String event, FireArg... args) {
        fire(id, event, FireOptions.DEFAULT, args);
    }

    @Override
    public void fire(ID id, String event, FireOptions options, FireArg... args) {
        FireTokens tokens = splitArgs(event, args);
        FireOptions opts = options == null ? FireOptions.DEFAULT : options;
        if (transactionTemplate != null) {
            transactionTemplate.executeWithoutResult(status -> doFire(id, event, tokens, opts));
        } else {
            // 容器无事务管理器时退化为逐语句自动提交（历史与 CAS 不再原子，见 README 说明）
            doFire(id, event, tokens, opts);
        }
    }

    @Override
    public boolean tryFire(ID id, String event, FireArg... args) {
        try {
            fire(id, event, args);
            return true;
        } catch (StateConflictException e) {
            // CAS 冲突（含重试耗尽）：确定性错误（非法流转/守卫拒绝）仍向上抛
            return false;
        }
    }

    private void doFire(ID id, String event, FireTokens tokens, FireOptions options) {
        String machine = runtime.name();
        RetryPolicy retry = runtime.getDefinition().getRetry();
        int attempts = retry == null ? 1 : retry.totalAttempts();

        for (int attempt = 1; ; attempt++) {
            // 1. 读当前态（version-column 启用时连版本一起读，乐观锁双保险）
            boolean withVersion = runtime.getDefinition().getVersionColumn() != null;
            StateAndVersion current = withVersion
                    ? stateStore.readStateWithVersion(id)
                    : stateStore.readState(id).map(StateAndVersion::of).orElse(null);
            if (current == null || current.state() == null) {
                throw new IllegalTransitionException(machine, null, event, Set.of());
            }
            String fromName = current.state();
            S from = runtime.stateOf(fromName);

            // 2. 路由查边（重试时以重读的最新状态重新解析，可能命中新的出边）
            TransitionSpec spec = runtime.getRouter().route(fromName, event)
                    .orElseThrow(() -> new IllegalTransitionException(
                            machine, fromName, event, runtime.getRouter().allowedEvents(fromName)));
            S to = runtime.stateOf(spec.getTo());

            StateTx<S, ID> tx = new TxContext(id, event, from, to, tokens.params);

            // 3. 守卫（CAS 之前，只读校验；通过后到 CAS 之间的间隙由 WHERE status=from 兜底）
            if (spec.getGuard() != null) {
                StateGuard<S, ID> guard = resolveGuard(spec.getGuard());
                if (!guard.test(tx)) {
                    throw new GuardRejectedException(machine, fromName, event);
                }
            }

            // 4. CAS：UPDATE ... SET status=to[, set列][, version+1] WHERE id=? AND status=from[ AND version=?]
            //    CAS 期间置 FireContext 标记，BYPASS 检测器据此豁免本条语句
            int affected;
            io.github.biglv666.statekit.bypass.FireContext.enter();
            try {
                affected = withVersion
                        ? stateStore.casTransition(id, fromName, spec.getTo(), tokens.sets, current.version())
                        : stateStore.casTransition(id, fromName, spec.getTo(), tokens.sets);
            } finally {
                io.github.biglv666.statekit.bypass.FireContext.exit();
            }

            if (affected > 0) {
                complete(id, event, tokens, options, fromName, spec, tx);
                return;
            }

            // CAS 未命中：log 策略静默；有重试额度则退避后以最新状态重试；否则抛冲突
            String actual = stateStore.readState(id).orElse(null);
            if (runtime.getDefinition().getConflictStrategy() == ConflictStrategy.LOG) {
                log.warn("状态机 [{}] 实体 [{}] 事件 [{}] CAS 未命中: 期望 [{}], 实际 [{}]（conflict-strategy=log, 不抛异常）",
                        machine, id, event, fromName, actual);
                return;
            }
            if (attempt < attempts) {
                log.info("状态机 [{}] 实体 [{}] 事件 [{}] 第 {} 次尝试未命中（期望 [{}], 实际 [{}]），{}ms 后重试",
                        machine, id, event, attempt, fromName, actual, retry.backoffMs());
                if (retry.backoffMs() > 0) {
                    try {
                        Thread.sleep(retry.backoffMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new StateConflictException(machine, id, fromName, actual);
                    }
                }
                continue;
            }
            throw new StateConflictException(machine, id, fromName, actual);
        }
    }

    /** CAS 成功后的公共收尾：动作 → 嵌套聚合回发 → 事件 → 历史 */
    private void complete(ID id, String event, FireTokens tokens, FireOptions options,
                          String fromName, TransitionSpec spec, StateTx<S, ID> tx) {
        String machine = runtime.name();

        // 5. 动作：与 CAS 同事务，行锁已持有；抛异常整体回滚（含 set 列）
        if (spec.getAction() != null) {
            StateAction<S, ID> action = resolveAction(spec.getAction());
            action.execute(tx);
        }

        // 5.5 嵌套聚合：子机器流转到终态后，按策略聚合并回发父事件（同事务）
        if (aggregator != null && runtime.getRouter().isFinal(spec.getTo())) {
            aggregator.maybeComplete(id, spec.getTo());
        }

        // 6. 发事件（进程内旁路观测，见 StateTransitedEvent 的监听契约）
        String operatorId = operatorResolver == null ? null : operatorResolver.resolve();
        String traceId = traceIdResolver == null ? null : traceIdResolver.resolve();
        LocalDateTime now = LocalDateTime.now();
        eventPublisher.publishEvent(
                new StateTransitedEvent(machine, id, fromName, spec.getTo(), event, operatorId, traceId, now));

        // 7. 历史（同事务；未开启时 historyRecorder 为 null，零开销；skipHistory 单次豁免）
        if (historyRecorder != null && !options.isSkipHistory()) {
            historyRecorder.record(new HistoryRecord(machine, String.valueOf(id),
                    fromName, spec.getTo(), event, operatorId, traceId, now));
        }
    }

    @Override
    public Optional<S> currentState(ID id) {
        return stateStore.readState(id).map(runtime::stateOf);
    }

    @Override
    public boolean isFinal(ID id) {
        String fromName = stateStore.readState(id)
                .orElseThrow(() -> new EntityNotFoundException(runtime.name(), id));
        return runtime.getRouter().isFinal(fromName);
    }

    @Override
    public Set<S> nextStates(ID id) {
        String fromName = stateStore.readState(id)
                .orElseThrow(() -> new EntityNotFoundException(runtime.name(), id));
        Set<S> result = new LinkedHashSet<>();
        for (String name : runtime.getRouter().nextStates(fromName)) {
            result.add(runtime.stateOf(name));
        }
        return result;
    }

    /** 令牌拆分结果：param 进上下文 map，set 进 SET 子句 map，二者严格隔离 */
    private FireTokens splitArgs(String event, FireArg[] args) {
        FireTokens tokens = new FireTokens();
        if (args == null) {
            return tokens;
        }
        for (FireArg arg : args) {
            switch (arg.kind()) {
                case PARAM -> {
                    if (tokens.params.putIfAbsent(arg.key(), arg.value()) != null) {
                        throw new IllegalArgumentException(
                                "状态机 [%s] 事件 [%s] 的 param [%s] 重复声明"
                                        .formatted(runtime.name(), event, arg.key()));
                    }
                }
                case SET -> {
                    if (tokens.sets.putIfAbsent(arg.key(), arg.value()) != null) {
                        throw new IllegalArgumentException(
                                "状态机 [%s] 事件 [%s] 的 set 列 [%s] 重复声明"
                                        .formatted(runtime.name(), event, arg.key()));
                    }
                }
            }
        }
        return tokens;
    }

    @SuppressWarnings("unchecked")
    private StateGuard<S, ID> resolveGuard(String beanName) {
        return (StateGuard<S, ID>) runtime.hookCache().computeIfAbsent("G:" + beanName, k -> {
            Object bean = beanFactory.getBean(beanName);
            if (!(bean instanceof StateGuard<?, ?>)) {
                throw new IllegalStateException("状态机 [%s] 引用的 guard bean [%s] 类型不是 StateGuard"
                        .formatted(runtime.name(), beanName));
            }
            return bean;
        });
    }

    @SuppressWarnings("unchecked")
    private StateAction<S, ID> resolveAction(String beanName) {
        return (StateAction<S, ID>) runtime.hookCache().computeIfAbsent("A:" + beanName, k -> {
            Object bean = beanFactory.getBean(beanName);
            if (!(bean instanceof StateAction<?, ?>)) {
                throw new IllegalStateException("状态机 [%s] 引用的 action bean [%s] 类型不是 StateAction"
                        .formatted(runtime.name(), beanName));
            }
            return bean;
        });
    }

    /** fire 令牌拆分结果 */
    private static final class FireTokens {
        final Map<String, Object> params = new LinkedHashMap<>();
        final Map<String, Object> sets = new LinkedHashMap<>();
    }

    /** StateTx 实现：不可变快照，守卫与动作共享同一实例 */
    private final class TxContext implements StateTx<S, ID> {

        private final ID entityId;
        private final String event;
        private final S from;
        private final S to;
        private final Map<String, Object> params;

        TxContext(ID entityId, String event, S from, S to, Map<String, Object> params) {
            this.entityId = entityId;
            this.event = event;
            this.from = from;
            this.to = to;
            this.params = params;
        }

        @Override
        public ID entityId() {
            return entityId;
        }

        @Override
        public String event() {
            return event;
        }

        @Override
        public S from() {
            return from;
        }

        @Override
        public S to() {
            return to;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T param(String key, Class<T> type) {
            Object value = params.get(key);
            if (value == null) {
                return null;
            }
            if (!type.isInstance(value)) {
                throw new ClassCastException("param [%s] 实际类型 %s 与期望类型 %s 不符"
                        .formatted(key, value.getClass().getName(), type.getName()));
            }
            return (T) value;
        }

        @Override
        public String operatorId() {
            return operatorResolver == null ? null : operatorResolver.resolve();
        }

        @Override
        public String traceId() {
            return traceIdResolver == null ? null : traceIdResolver.resolve();
        }
    }
}
