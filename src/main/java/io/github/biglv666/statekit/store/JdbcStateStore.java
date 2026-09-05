package io.github.biglv666.statekit.store;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 默认 JDBC 状态存取实现，面向业务表（表名/状态列/主键列由状态机声明给出）。
 * 每个状态机持有自己的实例（表不同），共用容器的 {@link JdbcTemplate}，
 * 自动参与调用方事务（JdbcTemplate 无独立事务语义，直取当前事务连接）。
 */
public class JdbcStateStore implements StateStore {

    private final JdbcTemplate jdbcTemplate;
    private final String table;
    private final String statusColumn;
    private final String idColumn;

    public JdbcStateStore(JdbcTemplate jdbcTemplate, String table, String statusColumn, String idColumn) {
        this.jdbcTemplate = jdbcTemplate;
        this.table = table;
        this.statusColumn = statusColumn;
        this.idColumn = idColumn;
    }

    @Override
    public Optional<String> readState(Object id) {
        String sql = "SELECT " + statusColumn + " FROM " + table + " WHERE " + idColumn + " = ?";
        return jdbcTemplate.query(sql, rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty(), id);
    }

    @Override
    public int casTransition(Object id, String from, String to, Map<String, Object> setColumns) {
        // SET 子句：status 恒在首位，附加列按 LinkedHashMap 顺序拼接保证 SQL 稳定
        Map<String, Object> sets = new LinkedHashMap<>();
        sets.put(statusColumn, to);
        if (setColumns != null) {
            sets.putAll(setColumns);
        }

        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        for (String column : sets.keySet()) {
            sql.append(column).append(" = ?, ");
        }
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE ").append(idColumn).append(" = ? AND ").append(statusColumn).append(" = ?");

        Object[] args = new Object[sets.size() + 2];
        int i = 0;
        for (Object value : sets.values()) {
            args[i++] = value;
        }
        args[i++] = id;
        args[i] = from;
        return jdbcTemplate.update(sql.toString(), args);
    }
}
