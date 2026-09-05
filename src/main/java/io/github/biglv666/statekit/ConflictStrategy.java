package io.github.biglv666.statekit;

/**
 * 状态冲突处理策略：CAS 更新（{@code UPDATE ... SET status=to WHERE id=? AND status=from}）
 * 影响行数为 0，即数据库当前状态与期望的 from 不一致时的行为。
 *
 * <p>冲突的典型来源：并发双流转（两个线程同时对同一实体 fire 同一事件，
 * 数据库行锁保证恰好一方的 CAS 命中）、状态已被其它路径修改、实体不存在。</p>
 */
public enum ConflictStrategy {

    /**
     * 抛出 {@code StateConflictException}（含期望态与实际态），由调用方决定如何处置。
     * 默认策略，推荐绝大多数场景使用——静默吞掉冲突会让调用方误以为流转成功。
     */
    THROW,

    /**
     * 仅记录 WARN 日志，{@code fire} 正常返回，不抛异常。
     * <p><b>注意：</b>本策略下调用方无法从返回值感知冲突（fire 签名为 void），
     * 只适用于「冲突可忽略」的场景，如定时任务对大批实体的补偿性流转。</p>
     */
    LOG
}
