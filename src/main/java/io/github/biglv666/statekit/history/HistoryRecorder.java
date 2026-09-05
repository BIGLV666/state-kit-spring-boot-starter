package io.github.biglv666.statekit.history;

/**
 * 流转历史写入 SPI：默认实现 {@link JdbcHistoryRecorder} 写关系型历史表，
 * 与 fire 主流程同一事务（业务回滚历史也回滚）。
 *
 * <p>仅当 {@code state-kit.history.enabled=true} 时装配；关闭时框架内不存在本 Bean。</p>
 */
@FunctionalInterface
public interface HistoryRecorder {

    /**
     * 写入一条流转历史。实现方必须参与当前事务（不得自开新事务），
     * 保证「业务回滚历史也回滚」的强一致语义。
     *
     * @param record 流转记录
     */
    void record(HistoryRecord record);
}
