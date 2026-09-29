package io.github.biglv666.statekit.event;

import java.time.LocalDateTime;

/**
 * 补偿调度事件（0.3.0+）：冲突补偿策略返回
 * {@link io.github.biglv666.statekit.compensation.CompensationDecision.Schedule}
 * 时发布。fire 正常返回，业务方监听此事件自行实现延迟补偿
 * （定时任务、消息队列延迟投递、OutboxPro 等）。
 *
 * <p>监听契约同 {@link StateTransitedEvent}：有副作用的监听用
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}。</p>
 */
public class CompensationScheduledEvent {

    private final String machine;
    private final Object entityId;
    private final String event;
    private final long delayMs;
    private final String expectedFrom;
    private final String actualState;
    private final LocalDateTime scheduledAt;

    public CompensationScheduledEvent(String machine, Object entityId, String event, long delayMs,
                                      String expectedFrom, String actualState, LocalDateTime scheduledAt) {
        this.machine = machine;
        this.entityId = entityId;
        this.event = event;
        this.delayMs = delayMs;
        this.expectedFrom = expectedFrom;
        this.actualState = actualState;
        this.scheduledAt = scheduledAt;
    }

    /** 状态机名 */
    public String getMachine() {
        return machine;
    }

    /** 实体主键 */
    public Object getEntityId() {
        return entityId;
    }

    /** 需要延迟重试的事件名 */
    public String getEvent() {
        return event;
    }

    /** 建议的延迟毫秒数（策略建议值，业务方自行决定实际调度） */
    public long getDelayMs() {
        return delayMs;
    }

    /** 冲突时 CAS 期望的 from 状态 */
    public String getExpectedFrom() {
        return expectedFrom;
    }

    /** 冲突后重读的实际状态 */
    public String getActualState() {
        return actualState;
    }

    /** 事件发布时间 */
    public LocalDateTime getScheduledAt() {
        return scheduledAt;
    }
}
