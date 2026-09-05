package io.github.biglv666.statekit.event;

import java.time.LocalDateTime;

/**
 * 流转完成事件：在 CAS 与动作都成功后、事务提交前发布（进程内同步）。
 *
 * <p><b>定位与约束：</b>本事件只是无副作用的旁路观测口（统计、缓存失效等），
 * 框架不对它做任何投递承诺，<b>不要</b>用它驱动后续业务——后续业务请写在
 * 动作里（需同事务）或业务方法 fire 之后显式调用。</p>
 *
 * <p>监听示例：</p>
 * <pre>{@code
 * // 有数据库写等副作用的监听，务必用 AFTER_COMMIT，事务回滚时不会触发：
 * @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
 * public void onTransited(StateTransitedEvent event) { ... }
 *
 * // 纯内存统计类旁路，普通 @EventListener 即可：
 * @EventListener
 * public void onMetrics(StateTransitedEvent event) { ... }
 * }</pre>
 */
public class StateTransitedEvent {

    private final String machine;
    private final Object entityId;
    private final String from;
    private final String to;
    private final String event;
    private final String operatorId;
    private final String traceId;
    private final LocalDateTime occurredAt;

    public StateTransitedEvent(String machine, Object entityId, String from, String to,
                               String event, String operatorId, String traceId, LocalDateTime occurredAt) {
        this.machine = machine;
        this.entityId = entityId;
        this.from = from;
        this.to = to;
        this.event = event;
        this.operatorId = operatorId;
        this.traceId = traceId;
        this.occurredAt = occurredAt;
    }

    /** 状态机名 */
    public String getMachine() {
        return machine;
    }

    /** 实体主键 */
    public Object getEntityId() {
        return entityId;
    }

    /** 流转前状态名 */
    public String getFrom() {
        return from;
    }

    /** 流转后状态名 */
    public String getTo() {
        return to;
    }

    /** 事件名 */
    public String getEvent() {
        return event;
    }

    /** 操作人标识（未接入或未登录为 null） */
    public String getOperatorId() {
        return operatorId;
    }

    /** 链路 traceId（无链路上下文为 null） */
    public String getTraceId() {
        return traceId;
    }

    /** 流转发生时间 */
    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
