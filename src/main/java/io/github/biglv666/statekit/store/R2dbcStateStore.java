package io.github.biglv666.statekit.store;

import io.github.biglv666.statekit.bypass.FireContext;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认 R2DBC 状态存取实现（0.2.0+）：面向业务表（表名/状态列/主键列由状态机声明给出），
 * 使用容器的 {@link DatabaseClient}，参与响应式事务（直取当前事务连接）。
 * 绑定使用命名参数，占位符由 Spring 按驱动方言翻译（PostgreSQL $n / MySQL、H2 ?）。
 */
public class R2dbcStateStore implements ReactiveStateStore {

    private final DatabaseClient databaseClient;
    private final String table;
    private final String statusColumn;
    private final String idColumn;

    public R2dbcStateStore(DatabaseClient databaseClient, String table, String statusColumn, String idColumn) {
        this.databaseClient = databaseClient;
        this.table = table;
        this.statusColumn = statusColumn;
        this.idColumn = idColumn;
    }

    @Override
    public Mono<String> readState(Object id) {
        return databaseClient.sql(
                        "SELECT " + statusColumn + " FROM " + table + " WHERE " + idColumn + " = :id")
                .bind("id", id)
                .map(row -> row.get(statusColumn, String.class))
                .first();
    }

    @Override
    public Mono<Integer> casTransition(Object id, String from, String to, Map<String, Object> setColumns) {
        // SET 子句：status 恒在首位，附加列按 LinkedHashMap 顺序拼接保证 SQL 稳定
        Map<String, Object> sets = new LinkedHashMap<>();
        sets.put(statusColumn, to);
        if (setColumns != null) {
            sets.putAll(setColumns);
        }

        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        int i = 0;
        for (String column : sets.keySet()) {
            sql.append(column).append(" = :p").append(i++).append(", ");
        }
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE ").append(idColumn).append(" = :__id AND ")
                .append(statusColumn).append(" = :__from");

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString());
        i = 0;
        for (Object value : sets.values()) {
            spec = spec.bind("p" + i++, value);
        }
        spec = spec.bind("__id", id).bind("__from", from);

        DatabaseClient.GenericExecuteSpec finalSpec = spec;
        // CAS 是框架的合法写：置 FireContext 标记豁免 BYPASS 拦截
        return Mono.defer(() -> {
            FireContext.enter();
            return finalSpec.fetch().rowsUpdated()
                    .map(Long::intValue)
                    .doFinally(signal -> FireContext.exit());
        });
    }
}
