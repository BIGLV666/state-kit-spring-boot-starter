package io.github.biglv666.statekit.define;

import io.github.biglv666.statekit.ConflictStrategy;

import java.util.List;

/**
 * 状态机定义（运行时模型）：yml DSL 与 Java DSL 两条通道统一产出此模型，
 * 框架启动时校验并据此生成 {@code StateMachine} Bean。
 */
public final class MachineDefinition {

    private final String name;
    private final Class<? extends Enum<?>> stateType;
    private final Class<?> idType;
    private final String table;
    private final String statusColumn;
    private final String idColumn;
    private final ConflictStrategy conflictStrategy;
    private final List<TransitionSpec> transitions;

    public MachineDefinition(String name, Class<? extends Enum<?>> stateType, Class<?> idType,
                             String table, String statusColumn, String idColumn,
                             ConflictStrategy conflictStrategy, List<TransitionSpec> transitions) {
        this.name = name;
        this.stateType = stateType;
        this.idType = idType;
        this.table = table;
        this.statusColumn = statusColumn;
        this.idColumn = idColumn;
        this.conflictStrategy = conflictStrategy;
        this.transitions = List.copyOf(transitions);
    }

    /** 状态机名，即 Bean 名，全局唯一 */
    public String getName() {
        return name;
    }

    /** 状态枚举类型 */
    public Class<? extends Enum<?>> getStateType() {
        return stateType;
    }

    /** 实体主键类型，默认 Long */
    public Class<?> getIdType() {
        return idType;
    }

    /** 业务表名 */
    public String getTable() {
        return table;
    }

    /** 状态列名，默认 status */
    public String getStatusColumn() {
        return statusColumn;
    }

    /** 主键列名，默认 id */
    public String getIdColumn() {
        return idColumn;
    }

    /** 冲突策略，默认 THROW */
    public ConflictStrategy getConflictStrategy() {
        return conflictStrategy;
    }

    /** 流转规则列表 */
    public List<TransitionSpec> getTransitions() {
        return transitions;
    }
}
