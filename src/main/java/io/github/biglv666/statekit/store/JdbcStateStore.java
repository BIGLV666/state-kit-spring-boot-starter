package io.github.biglv666.statekit.store;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 默认 JDBC 状态存取实现，面向业务表（表名/状态列/主键列由状态机声明给出）。
 * 每个状态机持有自己的实例（表不同），共用容器的 {@link JdbcTemplate}，
 * 自动参与调用方事务（JdbcTemplate 无独立事务语义，直取当前事务连接）。
 *
 * <p>0.2.0 起支持乐观锁双保险：声明 version-column 后，
 * 读态返回 version，CAS 的 WHERE 额外匹配 version 并在 SET 中自增。</p>
 */
public class JdbcStateStore implements StateStore {

    private final JdbcTemplate jdbcTemplate;
    private final String table;
    private final String statusColumn;
    private final String idColumn;
    /** 乐观锁版本列，未启用为 null */
    private final String versionColumn;

    public JdbcStateStore(JdbcTemplate jdbcTemplate, String table, String statusColumn, String idColumn) {
        this(jdbcTemplate, table, statusColumn, idColumn, null);
    }

    /** 0.2.0+：带 version 列的构造 */
    public JdbcStateStore(JdbcTemplate jdbcTemplate, String table, String statusColumn,
                          String idColumn, String versionColumn) {
        this.jdbcTemplate = jdbcTemplate;
        this.table = table;
        this.statusColumn = statusColumn;
        this.idColumn = idColumn;
        this.versionColumn = versionColumn;
    }

    @Override
    public Optional<String> readState(Object id) {
        String sql = "SELECT " + statusColumn + " FROM " + table + " WHERE " + idColumn + " = ?";
        return jdbcTemplate.query(sql, rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty(), id);
    }

    @Override
    public StateAndVersion readStateWithVersion(Object id) {
        if (versionColumn == null) {
            return StateStore.super.readStateWithVersion(id);
        }
        String sql = "SELECT " + statusColumn + ", " + versionColumn + " FROM " + table + " WHERE " + idColumn + " = ?";
        return jdbcTemplate.query(sql,
                rs -> rs.next()
                        ? new StateAndVersion(rs.getString(1), rs.getObject(2))
                        : null,
                id);
    }

    @Override
    public int casTransition(Object id, String from, String to, Map<String, Object> setColumns) {
        return doCas(id, from, to, setColumns, null);
    }

    @Override
    public int casTransition(Object id, String from, String to, Map<String, Object> setColumns, Object version) {
        return doCas(id, from, to, setColumns, version);
    }

    private int doCas(Object id, String from, String to, Map<String, Object> setColumns, Object version) {
        boolean useVersion = versionColumn != null && version != null;

        // SET 子句：status 恒在首位，附加列按 LinkedHashMap 顺序拼接保证 SQL 稳定；
        // version 列只出现在 SET 的自增表达式里，不参与参数绑定
        Map<String, Object> sets = new LinkedHashMap<>();
        sets.put(statusColumn, to);
        if (setColumns != null) {
            sets.putAll(setColumns);
        }

        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        java.util.List<Object> params = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> entry : sets.entrySet()) {
            if (useVersion && versionColumn.equals(entry.getKey())) {
                continue; // version 列的自增表达式单独拼接
            }
            sql.append(entry.getKey()).append(" = ?, ");
            params.add(entry.getValue());
        }
        if (useVersion) {
            sql.append(versionColumn).append(" = ").append(versionColumn).append(" + 1, ");
        }
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE ").append(idColumn).append(" = ? AND ").append(statusColumn).append(" = ?");
        params.add(id);
        params.add(from);
        if (useVersion) {
            sql.append(" AND ").append(versionColumn).append(" = ?");
            params.add(version);
        }

        return jdbcTemplate.update(sql.toString(), params.toArray());
    }
}
