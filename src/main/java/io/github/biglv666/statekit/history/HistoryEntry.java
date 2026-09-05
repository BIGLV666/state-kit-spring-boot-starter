package io.github.biglv666.statekit.history;

import java.time.LocalDateTime;

/**
 * 流转历史查询条目（读模型）。
 */
public final class HistoryEntry {

    private final long id;
    private final String machine;
    private final String entityId;
    private final String fromState;
    private final String toState;
    private final String event;
    private final String operatorId;
    private final String traceId;
    private final LocalDateTime createTime;

    public HistoryEntry(long id, String machine, String entityId, String fromState, String toState,
                        String event, String operatorId, String traceId, LocalDateTime createTime) {
        this.id = id;
        this.machine = machine;
        this.entityId = entityId;
        this.fromState = fromState;
        this.toState = toState;
        this.event = event;
        this.operatorId = operatorId;
        this.traceId = traceId;
        this.createTime = createTime;
    }

    /** 历史行主键 */
    public long getId() {
        return id;
    }

    /** 状态机名 */
    public String getMachine() {
        return machine;
    }

    /** 实体主键（字符串化存储） */
    public String getEntityId() {
        return entityId;
    }

    /** 流转前状态名 */
    public String getFromState() {
        return fromState;
    }

    /** 流转后状态名 */
    public String getToState() {
        return toState;
    }

    /** 事件名 */
    public String getEvent() {
        return event;
    }

    /** 操作人标识 */
    public String getOperatorId() {
        return operatorId;
    }

    /** 链路 traceId */
    public String getTraceId() {
        return traceId;
    }

    /** 流转发生时间 */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    @Override
    public String toString() {
        return "[%s] %s --%s--> %s (operator=%s, trace=%s)"
                .formatted(createTime, fromState, event, toState, operatorId, traceId);
    }
}
