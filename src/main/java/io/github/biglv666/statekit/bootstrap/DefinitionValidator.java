package io.github.biglv666.statekit.bootstrap;

import io.github.biglv666.statekit.StateAction;
import io.github.biglv666.statekit.StateGuard;
import io.github.biglv666.statekit.define.MachineDefinition;
import io.github.biglv666.statekit.define.TransitionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 启动期 fail-fast 校验器：所有状态机定义在注册动态 Bean 之前统一过一遍，
 * 任何错误直接阻止应用启动，错误信息定位到 machine + 具体流转。
 *
 * <p>校验项：状态列/表/主键列名合法性（SQL 注入白名单）、枚举成员存在性、
 * (from, event) 唯一性、action/guard bean 存在性与类型、状态可达性告警。</p>
 */
final class DefinitionValidator {

    private static final Logger log = LoggerFactory.getLogger(DefinitionValidator.class);

    /** SQL 标识符白名单：表名与列名都要拼 SQL，必须在启动期卡死 */
    private static final String IDENTIFIER = "^[A-Za-z][A-Za-z0-9_]*$";

    private DefinitionValidator() {
    }

    /**
     * 校验单个状态机定义。
     *
     * @param definition  定义模型
     * @param beanFactory 用于检查 action/guard bean 存在性
     * @throws IllegalStateException 任一校验不通过
     */
    static void validate(MachineDefinition definition, ConfigurableListableBeanFactory beanFactory) {
        String machine = definition.getName();

        if (definition.getStateType() == null || !definition.getStateType().isEnum()) {
            throw new IllegalStateException("状态机 [%s] 的 state-type 必须是枚举类型".formatted(machine));
        }
        if (definition.getStateType().getEnumConstants().length == 0) {
            throw new IllegalStateException("状态机 [%s] 的状态枚举 %s 没有任何常量"
                    .formatted(machine, definition.getStateType().getName()));
        }
        requireIdentifier(machine, "table", definition.getTable());
        requireIdentifier(machine, "status-column", definition.getStatusColumn());
        requireIdentifier(machine, "id-column", definition.getIdColumn());

        if (definition.getTransitions().isEmpty()) {
            throw new IllegalStateException("状态机 [%s] 未声明任何流转（transitions）".formatted(machine));
        }

        Set<String> enumNames = new HashSet<>();
        for (Object constant : definition.getStateType().getEnumConstants()) {
            enumNames.add(((Enum<?>) constant).name());
        }

        // (from, event) 唯一性 + 枚举成员存在性
        Set<String> edgeKeys = new HashSet<>();
        for (TransitionSpec spec : definition.getTransitions()) {
            if (spec.getFrom().isEmpty()) {
                throw new IllegalStateException("状态机 [%s] 流转 %s 的 from 不能为空".formatted(machine, spec));
            }
            if (spec.getEvent() == null || spec.getEvent().isBlank()) {
                throw new IllegalStateException("状态机 [%s] 流转 %s 的 event 不能为空".formatted(machine, spec));
            }
            for (String from : spec.getFrom()) {
                if (!enumNames.contains(from)) {
                    throw new IllegalStateException("状态机 [%s] 流转 %s 的 from 状态 [%s] 不在枚举 %s 中"
                            .formatted(machine, spec, from, definition.getStateType().getName()));
                }
                if (!edgeKeys.add(from + "@" + spec.getEvent())) {
                    throw new IllegalStateException("状态机 [%s] 存在重复的 (from, event) 流转: [%s] + [%s]，"
                            .formatted(machine, from, spec.getEvent())
                            + "同一状态上同一事件只能有一条出边");
                }
            }
            if (!enumNames.contains(spec.getTo())) {
                throw new IllegalStateException("状态机 [%s] 流转 %s 的 to 状态 [%s] 不在枚举 %s 中"
                        .formatted(machine, spec, spec.getTo(), definition.getStateType().getName()));
            }
            checkHookBean(machine, spec, spec.getAction(), StateAction.class, true, beanFactory);
            checkHookBean(machine, spec, spec.getGuard(), StateGuard.class, false, beanFactory);
        }

        // 可达性告警（非错误）：无任何入边的状态，若同时有出边，通常是预期起点；孤立状态提示可能漏声明
        Set<String> reachable = new HashSet<>();
        for (TransitionSpec spec : definition.getTransitions()) {
            reachable.add(spec.getTo());
        }
        for (String name : enumNames) {
            if (!reachable.contains(name)) {
                log.warn("状态机 [{}] 状态 [{}] 没有任何入边（不会被任何流转到达），若为初始状态可忽略",
                        machine, name);
            }
        }
    }

    private static void requireIdentifier(String machine, String what, String value) {
        if (value == null || !value.matches(IDENTIFIER)) {
            throw new IllegalStateException("状态机 [%s] 的 %s 非法: [%s]，必须是合法 SQL 标识符（字母开头，仅含字母/数字/下划线）"
                    .formatted(machine, what, value));
        }
    }

    private static void checkHookBean(String machine, TransitionSpec spec, String beanName,
                                      Class<?> requiredType, boolean isAction,
                                      ConfigurableListableBeanFactory beanFactory) {
        String what = isAction ? "action" : "guard";
        if (beanName == null || beanName.isBlank()) {
            return;
        }
        if (!beanFactory.containsBean(beanName)) {
            throw new IllegalStateException("状态机 [%s] 流转 %s 引用的 %s bean [%s] 不存在"
                    .formatted(machine, spec, what, beanName));
        }
        Class<?> type = beanFactory.getType(beanName);
        if (type != null && !requiredType.isAssignableFrom(type)) {
            throw new IllegalStateException("状态机 [%s] 流转 %s 引用的 %s bean [%s] 类型 %s 不是 %s"
                    .formatted(machine, spec, what, beanName, type.getName(), requiredType.getSimpleName()));
        }
    }
}
