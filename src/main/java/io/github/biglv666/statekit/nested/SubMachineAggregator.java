package io.github.biglv666.statekit.nested;

import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.core.MachineRuntime;
import io.github.biglv666.statekit.define.SubMachineBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

/**
 * 嵌套子状态机聚合器（0.2.0+）：子项流转到终态后触发，按
 * {@link SubMachineBinding} 的策略聚合父实体下的全部子项，满足则自动对父实体
 * fire {@code on-complete-event}。
 *
 * <p>回发的父流转与子项流转处于同一事务。回发前预检父实体存在性与回发边有效性，
 * 不满足（父实体缺失、父状态已被推进等）按 WARN 跳过，避免父 fire 异常把
 * 参与同事务的子项流转标记为回滚；预检通过后仍可能因并发冲突失败
 * （父机器 conflict-strategy=throw 时子项事务回滚，重试子项流转即可，幂等）。</p>
 *
 * <p>仅支持默认 JDBC 存储：聚合判定依赖对子项表的 SQL 统计，
 * 子机器声明了自定义 StateStore 时启动期即报错。</p>
 */
public class SubMachineAggregator {

    private static final Logger log = LoggerFactory.getLogger(SubMachineAggregator.class);

    private final JdbcTemplate jdbcTemplate;
    private final MachineRuntime<?> runtime;
    private final SubMachineBinding binding;
    /** 父状态机（泛型擦除后以 Object 视图回发事件） */
    private final StateMachine<Object, Object> parent;
    /** 父机器运行时（预检父实体表与状态用） */
    private final MachineRuntime<?> parentRuntime;
    /** 子机器的终态名集合（无出边的状态） */
    private final Set<String> finalStates;

    @SuppressWarnings("unchecked")
    public SubMachineAggregator(JdbcTemplate jdbcTemplate, MachineRuntime<?> runtime,
                                SubMachineBinding binding, MachineRuntime<?> parentRuntime,
                                StateMachine<?, ?> parent) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtime = runtime;
        this.binding = binding;
        this.parentRuntime = parentRuntime;
        this.parent = (StateMachine<Object, Object>) parent;
        this.finalStates = runtime.finalStates();
    }

    /**
     * 子项流转到终态后的聚合判定入口。
     *
     * @param subItemId  刚完成流转的子项主键
     * @param toState    子项的目标状态（必为终态，由调用方保证）
     */
    public void maybeComplete(Object subItemId, String toState) {
        List<Object> parents = jdbcTemplate.query(
                "SELECT " + binding.groupColumn() + " FROM " + runtime.getDefinition().getTable()
                        + " WHERE " + runtime.getDefinition().getIdColumn() + " = ?",
                (rs, i) -> rs.getObject(1), subItemId);
        if (parents.isEmpty()) {
            log.warn("状态机 [{}] 子项 [{}] 找不到 group 列 [{}] 的父实体引用，跳过聚合",
                    runtime.name(), subItemId, binding.groupColumn());
            return;
        }
        Object parentId = parents.get(0);

        // 预检父实体存在且当前状态确有回发边：不满足则 WARN 跳过，
        // 避免父 fire 的异常把参与同事务的子项流转标记为回滚
        List<String> parentStates = jdbcTemplate.query(
                "SELECT " + parentRuntime.getDefinition().getStatusColumn() + " FROM "
                        + parentRuntime.getDefinition().getTable() + " WHERE "
                        + parentRuntime.getDefinition().getIdColumn() + " = ?",
                (rs, i) -> rs.getString(1), parentId);
        if (parentStates.isEmpty() || parentStates.get(0) == null) {
            log.warn("状态机 [{}] 子项 [{}] 的父实体 [{}] 不存在，跳过聚合",
                    runtime.name(), subItemId, parentId);
            return;
        }
        if (parentRuntime.getRouter().route(parentStates.get(0), binding.onCompleteEvent()).isEmpty()) {
            log.warn("状态机 [{}] 子项 [{}] 的父实体 [{}] 当前状态 [{}] 已无回发边 [{}]（可能已被推进），跳过聚合",
                    runtime.name(), subItemId, parentId, parentStates.get(0), binding.onCompleteEvent());
            return;
        }

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + runtime.getDefinition().getTable()
                        + " WHERE " + binding.groupColumn() + " = ?",
                Integer.class, parentId);
        Integer finished = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + runtime.getDefinition().getTable()
                        + " WHERE " + binding.groupColumn() + " = ? AND "
                        + runtime.getDefinition().getStatusColumn() + " IN ("
                        + placeholders(finalStates.size()) + ")",
                Integer.class, placeholdersArgs(parentId));

        boolean satisfied = switch (binding.strategy()) {
            case ALL -> total != null && total > 0 && finished != null && finished >= total;
            case ANY -> finished != null && finished >= 1;
            case COUNT -> finished != null && finished >= binding.count();
        };
        if (!satisfied) {
            return;
        }
        log.info("状态机 [{}] 子项 [{}] 完成后聚合条件满足（{}: {}/{}），对父实体 [{}] 回发事件 [{}]",
                runtime.name(), subItemId, binding.strategy(), finished, total, parentId, binding.onCompleteEvent());
        try {
            parent.fire(parentId, binding.onCompleteEvent());
        } catch (io.github.biglv666.statekit.exception.StateConflictException e) {
            // 预检后仍被并发推进：按 WARN 处理（聚合条件已满足），不阻断子项事务
            log.warn("状态机 [{}] 聚合回发父事件 [{}] 冲突（父实体 [{}]）: {}",
                    runtime.name(), binding.onCompleteEvent(), parentId, e.getMessage());
        }
    }

    private Object[] placeholdersArgs(Object parentId) {
        Object[] result = new Object[1 + finalStates.size()];
        result[0] = parentId;
        int idx = 1;
        for (String state : finalStates) {
            result[idx++] = state;
        }
        return result;
    }

    private String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
