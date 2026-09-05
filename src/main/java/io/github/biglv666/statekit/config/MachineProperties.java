package io.github.biglv666.statekit.config;

import java.util.ArrayList;
import java.util.List;

/**
 * yml 中单个状态机的声明（{@code state-kit.machines.<name>} 下的结构）。
 * 启动时转换为 {@link io.github.biglv666.statekit.define.MachineDefinition}。
 */
public class MachineProperties {

    /**
     * 状态枚举全类名，如 {@code com.demo.OrderStatus}。
     */
    private Class<? extends Enum<?>> stateType;

    /**
     * 实体主键类型，默认 {@code Long}。影响 {@code StateMachine<S, ID>} 注入泛型的匹配。
     */
    private Class<?> idType = Long.class;

    /** 业务表名（必填） */
    private String table;

    /** 状态列名，默认 status；列内存枚举 name() 字符串 */
    private String statusColumn = "status";

    /** 主键列名，默认 id */
    private String idColumn = "id";

    /** 冲突策略：throw（默认）/ log */
    private String conflictStrategy = "throw";

    /** 流转规则列表 */
    private List<TransitionProperties> transitions = new ArrayList<>();

    public Class<? extends Enum<?>> getStateType() {
        return stateType;
    }

    public void setStateType(Class<? extends Enum<?>> stateType) {
        this.stateType = stateType;
    }

    public Class<?> getIdType() {
        return idType;
    }

    public void setIdType(Class<?> idType) {
        this.idType = idType;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getStatusColumn() {
        return statusColumn;
    }

    public void setStatusColumn(String statusColumn) {
        this.statusColumn = statusColumn;
    }

    public String getIdColumn() {
        return idColumn;
    }

    public void setIdColumn(String idColumn) {
        this.idColumn = idColumn;
    }

    public String getConflictStrategy() {
        return conflictStrategy;
    }

    public void setConflictStrategy(String conflictStrategy) {
        this.conflictStrategy = conflictStrategy;
    }

    public List<TransitionProperties> getTransitions() {
        return transitions;
    }

    public void setTransitions(List<TransitionProperties> transitions) {
        this.transitions = transitions;
    }

    /** yml 中单条流转规则声明 */
    public static class TransitionProperties {

        /**
         * 源状态名，单值或数组均可：
         * {@code from: CREATED} 会被 Spring Boot 绑定器包装为单元素列表，
         * {@code from: [CREATED, PAID]} 直接绑定多源列表。
         */
        private List<String> from = new ArrayList<>();

        /** 事件名 */
        private String event;

        /** 目标状态名 */
        private String to;

        /** 动作 bean 名（可选） */
        private String action;

        /** 守卫 bean 名（可选） */
        private String guard;

        public List<String> getFrom() {
            return from;
        }

        public void setFrom(List<String> from) {
            this.from = from == null ? new ArrayList<>() : new ArrayList<>(from);
        }

        public String getEvent() {
            return event;
        }

        public void setEvent(String event) {
            this.event = event;
        }

        public String getTo() {
            return to;
        }

        public void setTo(String to) {
            this.to = to;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getGuard() {
            return guard;
        }

        public void setGuard(String guard) {
            this.guard = guard;
        }
    }
}
