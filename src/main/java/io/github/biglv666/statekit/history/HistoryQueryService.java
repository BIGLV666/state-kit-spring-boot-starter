package io.github.biglv666.statekit.history;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 流转历史查询服务：按 (machine, entityId) 查时间序轨迹。
 * 仅当 {@code state-kit.history.enabled=true} 时装配。
 */
public class HistoryQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;

    public HistoryQueryService(JdbcTemplate jdbcTemplate, String tableName) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
    }

    /**
     * 查询实体的流转轨迹（按发生时间升序）。
     *
     * @param machine  状态机名
     * @param entityId 实体主键（内部字符串化匹配）
     * @return 时间序历史条目，无记录返回空列表
     */
    public List<HistoryEntry> query(String machine, Object entityId) {
        String entityIdStr = String.valueOf(entityId);
        return jdbcTemplate.query("""
                        SELECT id, machine, entity_id, from_state, to_state, event, operator_id, trace_id, create_time
                        FROM %s WHERE machine = ? AND entity_id = ? ORDER BY create_time ASC, id ASC"""
                        .formatted(tableName),
                (rs, rowNum) -> new HistoryEntry(
                        rs.getLong("id"),
                        rs.getString("machine"),
                        rs.getString("entity_id"),
                        rs.getString("from_state"),
                        rs.getString("to_state"),
                        rs.getString("event"),
                        rs.getString("operator_id"),
                        rs.getString("trace_id"),
                        rs.getTimestamp("create_time").toLocalDateTime()),
                machine, entityIdStr);
    }

    /**
     * 统计实体已流转次数。
     *
     * @param machine  状态机名
     * @param entityId 实体主键
     * @return 历史记录条数
     */
    public long count(String machine, Object entityId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE machine = ? AND entity_id = ?",
                Long.class, machine, String.valueOf(entityId));
        return count == null ? 0 : count;
    }
}
