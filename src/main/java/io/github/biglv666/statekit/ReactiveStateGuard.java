package io.github.biglv666.statekit;

import reactor.core.publisher.Mono;

/**
 * 响应式守卫钩子（0.2.0+）：仅用于 Reactive 状态机，与阻塞式
 * {@link StateGuard} 不混用。返回 false 即拒绝本次流转。
 */
@FunctionalInterface
public interface ReactiveStateGuard<S, ID> {

    /**
     * 校验是否允许本次流转。
     *
     * @param tx 流转上下文（同步快照，param 读取为阻塞内存操作）
     * @return Mono&lt;Boolean&gt;，false 拒绝
     */
    Mono<Boolean> test(StateTx<S, ID> tx);
}
