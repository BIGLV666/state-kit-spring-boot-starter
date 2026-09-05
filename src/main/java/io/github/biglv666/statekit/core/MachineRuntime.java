package io.github.biglv666.statekit.core;

import io.github.biglv666.statekit.define.MachineDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个状态机的启动期运行时：定义 + 编译后的路由表 + 状态枚举映射表 + 钩子 bean 缓存。
 * 启动期由 Registrar 构建并完成全部校验，运行期只读。
 *
 * @param <S> 状态枚举类型
 */
public final class MachineRuntime<S extends Enum<S>> {

    private final MachineDefinition definition;
    private final TransitionRouter router;
    /** 状态名 → 枚举常量 */
    private final Map<String, S> statesByName;
    /** 钩子 bean 缓存（action/guard 共用命名空间，前缀区分类型校验错误信息） */
    private final Map<String, Object> hookCache = new ConcurrentHashMap<>();

    public MachineRuntime(MachineDefinition definition, Class<S> stateType, TransitionRouter router) {
        this.definition = definition;
        this.router = router;
        this.statesByName = new HashMap<>();
        for (S constant : stateType.getEnumConstants()) {
            this.statesByName.put(constant.name(), constant);
        }
    }

    public MachineDefinition getDefinition() {
        return definition;
    }

    public TransitionRouter getRouter() {
        return router;
    }

    public Class<S> getStateType() {
        return statesByName.isEmpty() ? null : statesByName.values().iterator().next().getDeclaringClass();
    }

    /**
     * 状态名 → 枚举常量。
     *
     * @throws IllegalArgumentException 状态名不在枚举内（业务表出现脏数据时）
     */
    public S stateOf(String name) {
        S state = statesByName.get(name);
        if (state == null) {
            throw new IllegalArgumentException("状态机 [%s] 状态列出现未声明的值 [%s]，请在枚举 %s 中补充"
                    .formatted(definition.getName(), name, definition.getStateType().getName()));
        }
        return state;
    }

    /** 状态机名 */
    public String name() {
        return definition.getName();
    }

    /** 终态名集合（没有任何出边的状态），供嵌套聚合判定使用 */
    public java.util.Set<String> finalStates() {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        for (String stateName : statesByName.keySet()) {
            if (router.isFinal(stateName)) {
                result.add(stateName);
            }
        }
        return result;
    }

    /** 钩子 bean 缓存读写（框架内部使用） */
    public Map<String, Object> hookCache() {
        return hookCache;
    }
}
