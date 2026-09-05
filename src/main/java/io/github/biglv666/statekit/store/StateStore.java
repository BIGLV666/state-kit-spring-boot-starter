package io.github.biglv666.statekit.store;

import java.util.Map;
import java.util.Optional;

/**
 * 状态存取 SPI：框架对「状态存哪里、怎么改」的唯一抽象。
 *
 * <p>默认实现 {@link JdbcStateStore} 面向关系型业务表；需要把状态落到
 * 非标存储（Redis、ES 等）时，实现本接口并注册为 Spring Bean 即可整体替换，
 * 框架其余部分（路由、守卫、动作、事件、历史）不变。</p>
 *
 * <p><b>实现约束：</b>{@link #casTransition} 必须是单条原子语句——
 * 并发正确性完全依赖 {@code WHERE ... AND status = from} 条件与数据库行锁，
 * 不允许实现成「先读后写」两步。</p>
 */
public interface StateStore {

    /**
     * 读取实体当前状态名。
     *
     * @param id 实体主键
     * @return 状态名（枚举 name() 字符串）；实体不存在返回 empty，状态列为 null 也返回 empty
     */
    Optional<String> readState(Object id);

    /**
     * CAS 流转：单条原子 UPDATE，把状态从 from 改为 to，并附带写 set 列。
     *
     * @param id         实体主键
     * @param from       期望的当前状态名（进 WHERE 条件）
     * @param to         目标状态名
     * @param setColumns 附加落库列（列名 → 值），框架已做列名白名单校验；实现方必须
     *                   以 PreparedStatement 参数绑定写入值，禁止字符串拼接值
     * @return 影响行数：1 表示流转成功；0 表示状态与期望不符（并发冲突或实体不存在）
     */
    int casTransition(Object id, String from, String to, Map<String, Object> setColumns);

    /**
     * 读取状态与乐观锁版本（0.2.0+）。机器未启用 version-column 时 version 为 null。
     * 默认实现退化为 {@link #readState(Object)}，自定义实现可按需覆写。
     */
    default StateAndVersion readStateWithVersion(Object id) {
        return readState(id).map(StateAndVersion::of).orElse(null);
    }

    /**
     * 带乐观锁版本的 CAS 流转（0.2.0+）：WHERE 条件额外要求 version 匹配，
     * 并在 SET 子句中自增 version。version 为 null 时语义等同
     * {@link #casTransition(Object, String, String, Map)}。默认实现忽略 version。
     */
    default int casTransition(Object id, String from, String to, Map<String, Object> setColumns, Object version) {
        return casTransition(id, from, to, setColumns);
    }
}
