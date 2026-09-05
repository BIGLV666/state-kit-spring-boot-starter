package io.github.biglv666.statekit.define;

import io.github.biglv666.statekit.ConflictStrategy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Java DSL 构建器，由 {@link StateMachine#define(String, Class)} 进入。
 *
 * <p>{@code action} / {@code guard} 作用于<b>最近一次声明</b>的 transition，
 * 因此可紧跟在对应 transition 之后链式书写：</p>
 *
 * <pre>{@code
 * @Bean
 * StateMachineDefinition orderFlow() {
 *     return StateMachine.define("order", OrderStatus.class)
 *             .table("t_order", "status", "id")
 *             .idType(Long.class)
 *             .transition(CREATED, PAID, "PAY")
 *             .transition(PAID, SHIPPED, "SHIP")
 *             .transition(List.of(CREATED, PAID), CANCELLED, "CANCEL")     // 多源简写
 *             .transition(SHIPPED, DONE, "SIGN")
 *             .guard("orderSignGuard")                                     // 作用于 SIGN 这条
 *             .action("orderSignAction")
 *             .build();
 * }
 * }</pre>
 *
 * <p>定义 Bean 应当无副作用、只依赖常量——框架在启动早期（BeanFactory 后处理阶段）
 * 就会实例化它以完成校验与动态 Bean 注册。</p>
 *
 * @param <S> 状态枚举类型
 */
public final class DefinitionBuilder<S extends Enum<S>> {

    private final String name;
    private final Class<S> stateType;
    private Class<?> idType = Long.class;
    private String table;
    private String statusColumn = "status";
    private String idColumn = "id";
    private ConflictStrategy conflictStrategy = ConflictStrategy.THROW;
    private final List<TransitionSpec> transitions = new ArrayList<>();
    private String versionColumn;
    private RetryPolicy retry;
    private SubMachineBinding subBinding;
    private boolean reactive;

    public DefinitionBuilder(String name, Class<S> stateType) {
        this.name = name;
        this.stateType = stateType;
    }

    /**
     * 声明业务表与状态列、主键列。
     *
     * @param table        业务表名
     * @param statusColumn 状态列名（存枚举 name() 字符串）
     * @param idColumn     主键列名
     */
    public DefinitionBuilder<S> table(String table, String statusColumn, String idColumn) {
        this.table = table;
        this.statusColumn = statusColumn;
        this.idColumn = idColumn;
        return this;
    }

    /**
     * 实体主键类型，默认 {@code Long}。影响 {@code StateMachine<S, ID>} 注入泛型的匹配。
     */
    public DefinitionBuilder<S> idType(Class<?> idType) {
        this.idType = idType;
        return this;
    }

    /**
     * 冲突策略，默认 {@link ConflictStrategy#THROW}。
     */
    public DefinitionBuilder<S> conflictStrategy(ConflictStrategy conflictStrategy) {
        this.conflictStrategy = conflictStrategy;
        return this;
    }

    /**
     * 声明一条单源流转。
     */
    public DefinitionBuilder<S> transition(S from, S to, String event) {
        return transition(List.of(from), to, event);
    }

    /**
     * 声明一条多源流转（多源简写：多个 from 共用同一 event 与 to）。
     */
    public DefinitionBuilder<S> transition(Collection<S> froms, S to, String event) {
        if (froms == null || froms.isEmpty()) {
            throw new IllegalArgumentException("状态机 [%s] 的 transition 至少需要一个 from 状态".formatted(name));
        }
        Set<String> from = new LinkedHashSet<>();
        for (S s : froms) {
            from.add(s.name());
        }
        transitions.add(new TransitionSpec(from, event, to.name(), null, null));
        return this;
    }

    /**
     * 启用乐观锁双保险（0.2.0+）：CAS 的 WHERE 额外匹配 version 并在 SET 中自增。
     *
     * @param column 业务表版本列名（BIGINT/INT）
     */
    public DefinitionBuilder<S> versionColumn(String column) {
        this.versionColumn = column;
        return this;
    }

    /**
     * 冲突自动重试（0.2.0+）：CAS 未命中后事务内重读状态、重解析路由、重跑守卫再 CAS。
     *
     * @param maxAttempts 总尝试次数（含首次，≥1）
     * @param backoffMs   每次重试前等待毫秒数
     */
    public DefinitionBuilder<S> retry(int maxAttempts, long backoffMs) {
        this.retry = new RetryPolicy(maxAttempts, backoffMs);
        return this;
    }

    /**
     * 嵌套子机器绑定（0.2.0+）：本机器是父机器某状态下挂的子流程，
     * 子项到终态后按策略聚合并自动对父实体 fire 指定事件（见 {@link SubMachineBinding}）。
     */
    public DefinitionBuilder<S> subMachineOf(String parent, S parentState, String groupColumn,
                                             SubMachineBinding.Strategy strategy, int count, String onCompleteEvent) {
        this.subBinding = new SubMachineBinding(parent, parentState.name(), groupColumn, strategy, count, onCompleteEvent);
        return this;
    }

    /**
     * 同时注册 Reactive 状态机（0.2.0+）：额外生成 bean 名为 {@code <machine名>Reactive}
     * 的 {@code ReactiveStateMachine}（需要容器提供 ReactiveStateStore 或 DatabaseClient）。
     */
    public DefinitionBuilder<S> reactive() {
        this.reactive = true;
        return this;
    }

    /**
     * 为最近一次声明的 transition 挂载动作 bean。
     *
     * @param beanName 动作 bean 名，类型须为 {@code StateAction<S, ID>}
     */
    public DefinitionBuilder<S> action(String beanName) {
        return editLast("action", spec -> new TransitionSpec(spec.getFrom(), spec.getEvent(),
                spec.getTo(), beanName, spec.getGuard()));
    }

    /**
     * 为最近一次声明的 transition 挂载守卫 bean。
     *
     * @param beanName 守卫 bean 名，类型须为 {@code StateGuard<S, ID>}
     */
    public DefinitionBuilder<S> guard(String beanName) {
        return editLast("guard", spec -> new TransitionSpec(spec.getFrom(), spec.getEvent(),
                spec.getTo(), spec.getAction(), beanName));
    }

    private DefinitionBuilder<S> editLast(String what, java.util.function.UnaryOperator<TransitionSpec> editor) {
        if (transitions.isEmpty()) {
            throw new IllegalStateException("状态机 [%s] 的 %s 必须紧跟在一条 transition 之后声明".formatted(name, what));
        }
        TransitionSpec last = transitions.remove(transitions.size() - 1);
        transitions.add(editor.apply(last));
        return this;
    }

    /**
     * 完成定义。格式校验（name/枚举类型/表名/事件名）在此立即执行，
     * 跨规则校验（重复边、bean 存在性、可达性）由框架启动期统一做。
     */
    public MachineDefinition build() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("状态机名不允许为空");
        }
        if (stateType == null || !stateType.isEnum()) {
            throw new IllegalArgumentException("状态机 [%s] 的 stateType 必须是枚举类型".formatted(name));
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("状态机 [%s] 未声明业务表，请调用 table(...)"
                    .formatted(name));
        }
        for (TransitionSpec t : transitions) {
            if (t.getEvent() == null || t.getEvent().isBlank()) {
                throw new IllegalArgumentException("状态机 [%s] 存在未声明 event 的流转: %s".formatted(name, t));
            }
        }
        return new MachineDefinition(name, stateType, idType, table, statusColumn, idColumn,
                conflictStrategy, transitions, versionColumn, retry, subBinding, reactive);
    }
}
