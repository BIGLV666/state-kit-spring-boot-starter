package io.github.biglv666.statekit.history;

import reactor.core.publisher.Mono;

/**
 * 响应式历史写入 SPI（0.2.0+）：仅用于 Reactive 状态机，默认实现基于 R2DBC
 * 写历史表，参与 fire 的响应式事务。仅当 history.enabled 且机器 reactive 时装配。
 */
@FunctionalInterface
public interface ReactiveHistoryRecorder {

    /**
     * 写入一条流转历史。实现方必须参与当前响应式事务，保证业务回滚历史也回滚。
     *
     * @param record 流转记录
     * @return Mono&lt;Void&gt;
     */
    Mono<Void> record(HistoryRecord record);
}
