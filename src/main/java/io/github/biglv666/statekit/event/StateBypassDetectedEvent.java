package io.github.biglv666.statekit.event;

import java.time.LocalDateTime;

/**
 * BYPASS 绕改检测事件（0.2.0+）：检测到<b>绕过 fire</b> 的 status 修改时发布。
 *
 * <p>触发条件：{@code state-kit.bypass.mode=log/event} 且拦截到非框架线程执行的
 * {@code UPDATE <machine表> ... <status列> ...} 语句。默认 mode=off 不启用。</p>
 *
 * <p>监听建议（与 {@link StateTransitedEvent} 相同的契约）：有副作用的监听用
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}，纯告警上报用普通
 * {@code @EventListener}。框架只负责检测与发布，处置策略（告警、阻断）由监听方决定。</p>
 */
public class StateBypassDetectedEvent {

    private final String machine;
    private final String table;
    private final String sql;
    private final Object entityId;
    private final String operatorId;
    private final String traceId;
    private final LocalDateTime detectedAt;

    public StateBypassDetectedEvent(String machine, String table, String sql, Object entityId,
                                    String operatorId, String traceId, LocalDateTime detectedAt) {
        this.machine = machine;
        this.table = table;
        this.sql = sql;
        this.entityId = entityId;
        this.operatorId = operatorId;
        this.traceId = traceId;
        this.detectedAt = detectedAt;
    }

    /** 命中的状态机名 */
    public String getMachine() {
        return machine;
    }

    /** 被绕改的业务表名 */
    public String getTable() {
        return table;
    }

    /** 拦截到的 SQL 文本（参数已脱敏为占位原文，可能含 ? 占位符） */
    public String getSql() {
        return sql;
    }

    /** 尝试从 SQL 字面量解析的实体主键，参数化语句无法解析时为 null */
    public Object getEntityId() {
        return entityId;
    }

    /** 检测时刻的操作人标识（有 auth-kit 时） */
    public String getOperatorId() {
        return operatorId;
    }

    /** 检测时刻的链路 traceId */
    public String getTraceId() {
        return traceId;
    }

    /** 检测时间 */
    public LocalDateTime getDetectedAt() {
        return detectedAt;
    }

    @Override
    public String toString() {
        return "BYPASS: machine=%s table=%s entityId=%s sql=%s operator=%s trace=%s"
                .formatted(machine, table, entityId, sql, operatorId, traceId);
    }
}
