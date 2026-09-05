package io.github.biglv666.statekit;

import io.github.biglv666.statekit.define.DefinitionBuilder;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 响应式状态机（0.2.0+，WebFlux/R2DBC 通道）：与阻塞式 {@link StateMachine} 语义一致，
 * 全链路基于 Reactor。Bean 在机器声明 {@code reactive: true} 时注册，
 * bean 名 = machine 名 + {@code Reactive} 后缀。
 *
 * <p>配对接口：{@code ReactiveStateStore}（R2DBC 存取）、
 * {@code ReactiveStateAction}/{@code ReactiveStateGuard}（返回 Mono 的钩子）——
 * 与阻塞式钩子接口不混用。嵌套子机器聚合与 BYPASS 检测不适用于本通道
 * （前者依赖 JDBC 统计，后者依赖 ThreadLocal 标记）。</p>
 *
 * <p>事务：容器存在 {@code ReactiveTransactionManager} 时以 {@code TransactionalOperator}
 * 包裹 fire（加入外部响应式事务或自开）。</p>
 *
 * @param <S>  状态枚举类型
 * @param <ID> 实体主键类型
 */
public interface ReactiveStateMachine<S, ID> {

    /**
     * 响应式 fire：读态 → 路由 → 守卫 → CAS → 动作 → 事件 →（历史）。
     * 异常语义与阻塞版一致（IllegalTransition/GuardRejected/StateConflict）。
     */
    Mono<Void> fire(ID id, String event, FireArg... args);

    /** 带 {@link FireOptions} 的响应式 fire（skipHistory 豁免历史） */
    Mono<Void> fire(ID id, String event, FireOptions options, FireArg... args);

    /** 无冲突异常版本：CAS 冲突（含重试）返回 false，确定性错误照常以 error 信号传播 */
    Mono<Boolean> tryFire(ID id, String event, FireArg... args);

    /** 当前状态；实体不存在时为空 Mono */
    Mono<S> currentState(ID id);

    /** 是否终态；实体不存在时 error：EntityNotFoundException */
    Mono<Boolean> isFinal(ID id);

    /** 当前状态的直接后继集合；实体不存在时 error：EntityNotFoundException */
    Mono<Set<S>> nextStates(ID id);
}
