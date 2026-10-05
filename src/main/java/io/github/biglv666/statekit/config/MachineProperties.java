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

    /** 乐观锁版本列（0.2.0+，可选；启用后 CAS 额外匹配并自增 version） */
    private String versionColumn;

    /** 冲突自动重试（0.2.0+，可选） */
    private RetryProperties retry;

    /** 嵌套子机器绑定（0.2.0+，可选） */
    private SubProperties sub;

    /** 是否同时注册 Reactive 状态机（0.2.0+，默认 false） */
    private boolean reactive = false;

    /** 冲突补偿策略 bean 名（0.3.0+，可选） */
    private String compensation;

    /** 停留超时自动流转声明（0.5.0+，可选；声明任一条即装配扫描器） */
    private List<TimerProperties> timers = new ArrayList<>();

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

    public String getVersionColumn() {
        return versionColumn;
    }

    public void setVersionColumn(String versionColumn) {
        this.versionColumn = versionColumn;
    }

    public RetryProperties getRetry() {
        return retry;
    }

    public void setRetry(RetryProperties retry) {
        this.retry = retry;
    }

    public SubProperties getSub() {
        return sub;
    }

    public void setSub(SubProperties sub) {
        this.sub = sub;
    }

    public boolean isReactive() {
        return reactive;
    }

    public void setReactive(boolean reactive) {
        this.reactive = reactive;
    }

    public String getCompensation() {
        return compensation;
    }

    public void setCompensation(String compensation) {
        this.compensation = compensation;
    }

    public List<TimerProperties> getTimers() {
        return timers;
    }

    public void setTimers(List<TimerProperties> timers) {
        this.timers = timers;
    }

    /** 冲突自动重试声明（0.2.0+） */
    public static class RetryProperties {

        /** 总尝试次数（含首次，≥1），默认 1 = 不重试 */
        private int maxAttempts = 1;

        /** 每次重试前等待毫秒数，默认 0 */
        private long backoffMs = 0;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getBackoffMs() {
            return backoffMs;
        }

        public void setBackoffMs(long backoffMs) {
            this.backoffMs = backoffMs;
        }
    }

    /** 嵌套子机器绑定声明（0.2.0+） */
    public static class SubProperties {

        /** 父状态机名 */
        private String parent;

        /** 父机器上挂载子流程的状态名 */
        private String parentState;

        /** 子项表中指向父实体主键的列名 */
        private String groupColumn;

        /** 聚合策略：all（会签）/ any（或签）/ count（满 n 个） */
        private String strategy = "all";

        /** strategy=count 时的阈值 */
        private int count = 0;

        /** 聚合满足后对父实体自动触发的事件名 */
        private String onCompleteEvent;

        public String getParent() {
            return parent;
        }

        public void setParent(String parent) {
            this.parent = parent;
        }

        public String getParentState() {
            return parentState;
        }

        public void setParentState(String parentState) {
            this.parentState = parentState;
        }

        public String getGroupColumn() {
            return groupColumn;
        }

        public void setGroupColumn(String groupColumn) {
            this.groupColumn = groupColumn;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public String getOnCompleteEvent() {
            return onCompleteEvent;
        }

        public void setOnCompleteEvent(String onCompleteEvent) {
            this.onCompleteEvent = onCompleteEvent;
        }
    }

    /** 停留超时自动流转声明（0.5.0+），见 {@link TimerSpec} 的精确性边界 */
    public static class TimerProperties {

        /** 触发条件状态（实体当前停留的状态） */
        private String from;

        /** 停留时长阈值（Spring Boot Duration 绑定：30m / 12h / 45s 等），必须为正 */
        private java.time.Duration after;

        /** 到期后触发的事件，必须是 from 状态的合法出边 */
        private String event;

        /** 业务表中"进入该状态时间"的列名，必须只在进入该状态时更新 */
        private String sinceColumn;

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public java.time.Duration getAfter() {
            return after;
        }

        public void setAfter(java.time.Duration after) {
            this.after = after;
        }

        public String getEvent() {
            return event;
        }

        public void setEvent(String event) {
            this.event = event;
        }

        public String getSinceColumn() {
            return sinceColumn;
        }

        public void setSinceColumn(String sinceColumn) {
            this.sinceColumn = sinceColumn;
        }
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

        /** 事件描述（0.3.0+，供 availableActions 与导出，可选） */
        private String description;

        /** 期望的 param 键名列表（0.3.0+，供前端表单提示，可选） */
        private java.util.List<String> params = new ArrayList<>();

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

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public java.util.List<String> getParams() {
            return params;
        }

        public void setParams(java.util.List<String> params) {
            this.params = params;
        }
    }
}
