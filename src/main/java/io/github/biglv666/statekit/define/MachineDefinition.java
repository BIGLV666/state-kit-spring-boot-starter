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
    /** 乐观锁版本列，未启用为 null（0.2.0+） */
    private final String versionColumn;
    /** 冲突自动重试策略，null = 不重试（0.2.0+） */
    private final RetryPolicy retry;
    /** 嵌套子机器绑定，null = 独立机器（0.2.0+） */
    private final SubMachineBinding subBinding;
    /** 是否同时注册 Reactive 状态机（0.2.0+） */
    private final boolean reactive;

    public MachineDefinition(String name, Class<? extends Enum<?>> stateType, Class<?> idType,
                             String table, String statusColumn, String idColumn,
                             ConflictStrategy conflictStrategy, List<TransitionSpec> transitions) {
        this(name, stateType, idType, table, statusColumn, idColumn,
                conflictStrategy, transitions, null, null, null, false);
    }

    /** 0.2.0+ 全参构造 */
    public MachineDefinition(String name, Class<? extends Enum<?>> stateType, Class<?> idType,
                             String table, String statusColumn, String idColumn,
                             ConflictStrategy conflictStrategy, List<TransitionSpec> transitions,
                             String versionColumn, RetryPolicy retry,
                             SubMachineBinding subBinding, boolean reactive) {
        this.name = name;
        this.stateType = stateType;
        this.idType = idType;
        this.table = table;
        this.statusColumn = statusColumn;
        this.idColumn = idColumn;
        this.conflictStrategy = conflictStrategy;
        this.transitions = List.copyOf(transitions);
        this.versionColumn = versionColumn;
        this.retry = retry;
        this.subBinding = subBinding;
        this.reactive = reactive;
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

    /** 乐观锁版本列，未启用为 null（0.2.0+） */
    public String getVersionColumn() {
        return versionColumn;
    }

    /** 冲突自动重试策略，null = 不重试（0.2.0+） */
    public RetryPolicy getRetry() {
        return retry;
    }

    /** 嵌套子机器绑定，null = 独立机器（0.2.0+） */
    public SubMachineBinding getSubBinding() {
        return subBinding;
    }

    /** 是否同时注册 Reactive 状态机（0.2.0+） */
    public boolean isReactive() {
        return reactive;
    }
}
