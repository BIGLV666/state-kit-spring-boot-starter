package io.github.biglv666.statekit.store;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 响应式状态存取 SPI（0.2.0+）：Reactive 状态机对「状态存哪里、怎么改」的抽象，
 * 默认实现 {@link R2dbcStateStore} 基于 R2DBC。需要非标存储时实现本接口注册为 Bean。
 *
 * <p><b>实现约束</b>与阻塞版一致：{@link #casTransition} 必须是单条原子语句，
 * 并发正确性完全依赖 {@code WHERE ... AND status = from} 条件。</p>
 */
public interface ReactiveStateStore {

    /**
     * 读取实体当前状态名。
     *
     * @return 状态名；实体不存在或状态列为空时为空 Mono
     */
    Mono<String> readState(Object id);

    /**
     * CAS 流转：单条原子 UPDATE，把状态从 from 改为 to，并附带写 set 列。
     *
     * @return Mono&lt;Integer&gt;：1 流转成功；0 状态与期望不符（并发冲突或实体不存在）
     */
    Mono<Integer> casTransition(Object id, String from, String to, Map<String, Object> setColumns);
}
