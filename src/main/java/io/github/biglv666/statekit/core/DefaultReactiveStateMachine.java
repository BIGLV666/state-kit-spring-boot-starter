package io.github.biglv666.statekit.core;

import io.github.biglv666.statekit.FireArg;
import io.github.biglv666.statekit.FireOptions;
import io.github.biglv666.statekit.ReactiveStateAction;
import io.github.biglv666.statekit.ReactiveStateGuard;
import io.github.biglv666.statekit.ReactiveStateMachine;
import io.github.biglv666.statekit.StateTx;
import io.github.biglv666.statekit.context.OperatorResolver;
import io.github.biglv666.statekit.context.TraceIdResolver;
import io.github.biglv666.statekit.define.TransitionSpec;
import io.github.biglv666.statekit.event.StateTransitedEvent;
import io.github.biglv666.statekit.exception.EntityNotFoundException;
import io.github.biglv666.statekit.exception.GuardRejectedException;
import io.github.biglv666.statekit.exception.IllegalTransitionException;
import io.github.biglv666.statekit.exception.StateConflictException;
import io.github.biglv666.statekit.history.HistoryRecord;
import io.github.biglv666.statekit.history.ReactiveHistoryRecorder;
import io.github.biglv666.statekit.store.ReactiveStateStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@link ReactiveStateMachine} 默认实现（0.2.0+）：与阻塞版
 * {@link DefaultStateMachine} 相同的流转语义（读态→路由→守卫→CAS→动作→事件→历史），
 * 全链路 Reactor。不参与嵌套聚合与 BYPASS ThreadLocal 检测（见接口说明）。
 *
 * <p>动作 / 守卫 bean 首次使用时按名解析并缓存（启动期已校验存在性），
 * 缓存与其他机器共享命名空间时按类型检查互不干扰。</p>
 *
 * @param <S>  状态枚举类型
 * @param <ID> 实体主键类型
 */
public class DefaultReactiveStateMachine<S extends Enum<S>, ID> implements ReactiveStateMachine<S, ID> {

    private final MachineRuntime<S> runtime;
    private final ReactiveStateStore stateStore;
    private final TransactionalOperator transactionalOperator;
    private final OperatorResolver operatorResolver;
    private final TraceIdResolver traceIdResolver;
    private final ReactiveHistoryRecorder historyRecorder;
    private final ApplicationEventPublisher eventPublisher;
    private final org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory;
    private final Map<String, Object> hookCache = new LinkedHashMap<>();

    public DefaultReactiveStateMachine(MachineRuntime<S> runtime, ReactiveStateStore stateStore,
                                       TransactionalOperator transactionalOperator,
                                       OperatorResolver operatorResolver, TraceIdResolver traceIdResolver,
                                       ReactiveHistoryRecorder historyRecorder,
                                       ApplicationEventPublisher eventPublisher,
                                       org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
        this.runtime = runtime;
        this.stateStore = stateStore;
        this.transactionalOperator = transactionalOperator;
        this.operatorResolver = operatorResolver;
        this.traceIdResolver = traceIdResolver;
        this.historyRecorder = historyRecorder;
        this.eventPublisher = eventPublisher;
        this.beanFactory = beanFactory;
    }

    @Override
    public Mono<Void> fire(ID id, String event, FireArg... args) {
        return fire(id, event, FireOptions.DEFAULT, args);
    }

    @Override
    public Mono<Void> fire(ID id, String event, FireOptions options, FireArg... args) {
        FireTokens tokens = splitArgs(event, args);
        FireOptions opts = options == null ? FireOptions.DEFAULT : options;
        Mono<Void> pipeline = doFire(id, event, tokens, opts);
        return transactionalOperator != null ? transactionalOperator.transactional(pipeline) : pipeline;
    }

    @Override
    public Mono<Boolean> tryFire(ID id, String event, FireArg... args) {
        return fire(id, event, args)
                .thenReturn(true)
                .onErrorResume(StateConflictException.class, e -> Mono.just(false));
    }

    private Mono<Void> doFire(ID id, String event, FireTokens tokens, FireOptions options) {
        String machine = runtime.name();
        return stateStore.readState(id)
                .switchIfEmpty(Mono.error(() -> new IllegalTransitionException(machine, null, event, Set.of())))
                .flatMap(fromName -> {
                    TransitionSpec spec = runtime.getRouter().route(fromName, event)
                            .orElseThrow(() -> new IllegalTransitionException(
                                    machine, fromName, event, runtime.getRouter().allowedEvents(fromName)));
                    S from = runtime.stateOf(fromName);
                    S to = runtime.stateOf(spec.getTo());
                    StateTx<S, ID> tx = new TxContext(id, event, from, to, tokens.params);

                    Mono<Integer> cas = performGuardAndCas(spec, tx, fromName, event, tokens.sets);
                    return cas.flatMap(affected -> {
                        if (affected == 0) {
                            return stateStore.readState(id).defaultIfEmpty("")
                                    .flatMap(actual -> {
                                        if (runtime.getDefinition().getConflictStrategy()
                                                == io.github.biglv666.statekit.ConflictStrategy.LOG) {
                                            return Mono.empty();
                                        }
                                        return Mono.error(new StateConflictException(machine, id, fromName, actual));
                                    });
                        }
                        return complete(id, event, options, fromName, spec, tx);
                    });
                });
    }

    /** 守卫 → CAS；守卫 error/False 均拒绝 */
    private Mono<Integer> performGuardAndCas(TransitionSpec spec, StateTx<S, ID> tx,
                                             String fromName, String event, Map<String, Object> setColumns) {
        Mono<Boolean> guardResult = spec.getGuard() == null
                ? Mono.just(true)
                : resolveGuard(spec.getGuard()).test(tx)
                        .defaultIfEmpty(false);
        return guardResult.flatMap(allowed -> {
            if (!allowed) {
                return Mono.error(new GuardRejectedException(runtime.name(), fromName, event));
            }
            return stateStore.casTransition(tx.entityId(), fromName, spec.getTo(), setColumns);
        });
    }

    /** CAS 成功后的收尾：动作 → 事件 → 历史 */
    private Mono<Void> complete(ID id, String event, FireOptions options,
                                String fromName, TransitionSpec spec, StateTx<S, ID> tx) {
        String machine = runtime.name();
        Mono<Void> action = spec.getAction() == null
                ? Mono.empty()
                : resolveAction(spec.getAction()).execute(tx).then();

        return action.then(Mono.defer(() -> {
            String operatorId = operatorResolver == null ? null : operatorResolver.resolve();
            String traceId = traceIdResolver == null ? null : traceIdResolver.resolve();
            LocalDateTime now = LocalDateTime.now();
            eventPublisher.publishEvent(new StateTransitedEvent(
                    machine, id, fromName, spec.getTo(), event, operatorId, traceId, now));
            Mono<Void> history = historyRecorder != null && !options.isSkipHistory()
                    ? historyRecorder.record(new HistoryRecord(machine, String.valueOf(id),
                            fromName, spec.getTo(), event, operatorId, traceId, now))
                    : Mono.empty();
            return history;
        }));
    }

    @Override
    public Mono<S> currentState(ID id) {
        return stateStore.readState(id).map(runtime::stateOf);
    }

    @Override
    public Mono<Boolean> isFinal(ID id) {
        return stateStore.readState(id)
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException(runtime.name(), id)))
                .map(fromName -> runtime.getRouter().isFinal(fromName));
    }

    @Override
    public Mono<Set<S>> nextStates(ID id) {
        return stateStore.readState(id)
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException(runtime.name(), id)))
                .map(fromName -> {
                    Set<S> result = new LinkedHashSet<>();
                    for (String name : runtime.getRouter().nextStates(fromName)) {
                        result.add(runtime.stateOf(name));
                    }
                    return result;
                });
    }

    // ---- 内部工具 ----

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
    private ReactiveStateGuard<S, ID> resolveGuard(String beanName) {
        return (ReactiveStateGuard<S, ID>) hookCache.computeIfAbsent("G:" + beanName, k -> {
            Object bean = beanFactory.getBean(beanName);
            if (!(bean instanceof ReactiveStateGuard<?, ?>)) {
                throw new IllegalStateException("状态机 [%s] 引用的 guard bean [%s] 类型不是 ReactiveStateGuard"
                        .formatted(runtime.name(), beanName));
            }
            return bean;
        });
    }

    @SuppressWarnings("unchecked")
    private ReactiveStateAction<S, ID> resolveAction(String beanName) {
        return (ReactiveStateAction<S, ID>) hookCache.computeIfAbsent("A:" + beanName, k -> {
            Object bean = beanFactory.getBean(beanName);
            if (!(bean instanceof ReactiveStateAction<?, ?>)) {
                throw new IllegalStateException("状态机 [%s] 引用的 action bean [%s] 类型不是 ReactiveStateAction"
                        .formatted(runtime.name(), beanName));
            }
            return bean;
        });
    }

    private static final class FireTokens {
        final Map<String, Object> params = new LinkedHashMap<>();
        final Map<String, Object> sets = new LinkedHashMap<>();
    }

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
