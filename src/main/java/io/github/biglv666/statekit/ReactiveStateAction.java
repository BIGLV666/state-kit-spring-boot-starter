package io.github.biglv666.statekit;

import reactor.core.publisher.Mono;

/**
 * 响应式动作钩子（0.2.0+）：仅用于 Reactive 状态机，与阻塞式
 * {@link StateAction} 不混用。error 信号导致整个 fire 失败回滚。
 *
 * <p>响应式通道中的业务写请使用 R2DBC 客户端；阻塞调用（JDBC/远程同步调用）
 * 请包裹 {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}，
 * 不要在事件循环线程上阻塞。</p>
 */
@FunctionalInterface
public interface ReactiveStateAction<S, ID> {

    /**
     * 执行流转后动作。
     *
     * @param tx 流转上下文
     * @return Mono&lt;Void&gt;，error 信号整体回滚
     */
    Mono<Void> execute(StateTx<S, ID> tx);
}
