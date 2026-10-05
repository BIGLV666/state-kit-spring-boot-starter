package io.github.biglv666.statekit.metrics;

import io.github.biglv666.statekit.exception.GuardRejectedException;
import io.github.biglv666.statekit.exception.IllegalTransitionException;
import io.github.biglv666.statekit.exception.StateConflictException;

/**
 * fire 埋点 SPI（0.4.0+）：每次 fire 恰好产生一条结果记录（成功/失败皆记），
 * 另有重试与补偿决策两类计数。核心包不依赖 micrometer——实现由
 * {@code MicrometerFireMetrics} 提供，容器无 {@code MeterRegistry} 时装配 {@link #NOOP}。
 *
 * <p>业务可自行实现本接口注册为 Bean 替换默认 micrometer 实现
 * （如对接自研监控），注册后对所有状态机生效。</p>
 *
 * <p>指标语义：</p>
 * <ul>
 *     <li>{@link #record}：每次 fire 一条，outcome 枚举见各常量；</li>
 *     <li>{@link #recordRetry}：CAS 冲突自动重试的每次 attempt（不含首次）；</li>
 *     <li>{@link #recordCompensation}：补偿策略三分支决策分布（abort 也计）。</li>
 * </ul>
 */
public interface FireMetrics {

    /** 流转成功 */
    String OUTCOME_SUCCESS = "success";
    /** 非法流转（当前状态无该事件出边 / 实体不存在） */
    String OUTCOME_ILLEGAL = "illegal";
    /** 守卫拒绝 */
    String OUTCOME_GUARD_REJECTED = "guard_rejected";
    /** CAS 冲突且最终抛出 StateConflictException（含重试/补偿耗尽） */
    String OUTCOME_CONFLICT = "conflict";
    /** CAS 冲突但 conflict-strategy=log 静默（fire 正常返回） */
    String OUTCOME_CONFLICT_LOG = "conflict_log";
    /** 冲突后补偿策略以新事件重路由（外层 fire 正常返回，内层 fire 另有自己的记录） */
    String OUTCOME_COMPENSATION_RETRY = "compensation_retry";
    /** 冲突后补偿策略延迟调度（fire 正常返回，发布 CompensationScheduledEvent） */
    String OUTCOME_COMPENSATION_SCHEDULE = "compensation_schedule";
    /** 其余异常（动作抛出的业务异常等）——fire 以未知异常结束 */
    String OUTCOME_ERROR = "error";

    /** 补偿决策：重路由新事件 */
    String DECISION_RETRY_WITH = "retryWith";
    /** 补偿决策：延迟调度 */
    String DECISION_SCHEDULE = "schedule";
    /** 补偿决策：放弃（抛 StateConflictException，与无策略一致） */
    String DECISION_ABORT = "abort";

    /** 无指标实现时的空实现：全部零开销 */
    FireMetrics NOOP = new FireMetrics() {
        @Override
        public void record(String machine, String event, String from, String to,
                           String outcome, long durationNanos) {
        }

        @Override
        public void recordRetry(String machine, String event) {
        }

        @Override
        public void recordCompensation(String machine, String event, String actualState, String decision) {
        }
    };

    /**
     * 记录一次 fire 的结果。from/to 取不到时传 null（实现侧以 "-" 呈现）。
     *
     * @param machine       状态机名
     * @param event         事件名
     * @param from          最终 attempt 的来源状态（成功路径）或异常携带的期望态
     * @param to            目标状态，仅成功路径有值
     * @param outcome       结果枚举，见 OUTCOME_* 常量
     * @param durationNanos 本次 fire 全程耗时（含事务边界与重试退避）
     */
    void record(String machine, String event, String from, String to,
                String outcome, long durationNanos);

    /** 记录一次 CAS 冲突后的自动重试 attempt */
    void recordRetry(String machine, String event);

    /** 记录一次补偿策略决策（machine/event/实际状态/决策枚举） */
    void recordCompensation(String machine, String event, String actualState, String decision);

    /**
     * 记录一次停留超时扫描（0.5.0+）。default 空实现：自定义 FireMetrics 无需改动即可升级。
     *
     * @param machine       状态机名
     * @param timer         timer 标识（{@code from@event}）
     * @param due           本轮扫描发现的到期实体数
     * @param fired         实际成功推进数（其余为冲突/非法/守卫拒绝等，下轮重试）
     * @param durationNanos 本 timer 扫描耗时（不含 fire 内部耗时）
     */
    default void recordTimerScan(String machine, String timer, long due, long fired, long durationNanos) {
    }

    /** 异常 → outcome 枚举映射：三类状态异常精确归因，其余归入 error */
    static String outcomeOf(Throwable e) {
        if (e instanceof IllegalTransitionException) {
            return OUTCOME_ILLEGAL;
        }
        if (e instanceof GuardRejectedException) {
            return OUTCOME_GUARD_REJECTED;
        }
        if (e instanceof StateConflictException) {
            return OUTCOME_CONFLICT;
        }
        return OUTCOME_ERROR;
    }

    /** 异常 → from 状态 tag 取值：从异常携带的字段提取，取不到返回 null */
    static String fromOf(Throwable e) {
        if (e instanceof IllegalTransitionException x) {
            return x.getFrom();
        }
        if (e instanceof GuardRejectedException x) {
            return x.getFrom();
        }
        if (e instanceof StateConflictException x) {
            return x.getExpected();
        }
        return null;
    }
}
