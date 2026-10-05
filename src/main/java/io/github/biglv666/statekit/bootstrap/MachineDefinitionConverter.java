package io.github.biglv666.statekit.bootstrap;

import io.github.biglv666.statekit.ConflictStrategy;
import io.github.biglv666.statekit.config.MachineProperties;
import io.github.biglv666.statekit.define.MachineDefinition;
import io.github.biglv666.statekit.define.RetryPolicy;
import io.github.biglv666.statekit.define.SubMachineBinding;
import io.github.biglv666.statekit.define.TransitionSpec;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * yml 通道 → 运行时定义模型转换器。仅做结构映射，
 * 语义校验（枚举成员、重复边、bean 存在性）统一由 {@link DefinitionValidator} 执行。
 */
final class MachineDefinitionConverter {

    private MachineDefinitionConverter() {
    }

    /**
     * 转换单个 yml 状态机声明。
     *
     * @param name       状态机名（yml 中 machines 的 key）
     * @param properties yml 声明
     * @return 运行时定义
     * @throws IllegalStateException state-type 缺失或冲突策略非法等结构错误
     */
    static MachineDefinition convert(String name, MachineProperties properties) {
        if (properties.getStateType() == null) {
            throw new IllegalStateException("状态机 [%s] 未声明 state-type".formatted(name));
        }
        ConflictStrategy strategy;
        try {
            strategy = ConflictStrategy.valueOf(
                    properties.getConflictStrategy().toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("状态机 [%s] 的 conflict-strategy 非法: [%s]，只支持 throw / log"
                    .formatted(name, properties.getConflictStrategy()), e);
        }

        List<TransitionSpec> transitions = properties.getTransitions().stream()
                .map(t -> new TransitionSpec(new LinkedHashSet<>(t.getFrom()), t.getEvent(), t.getTo(),
                        t.getAction(), t.getGuard(), t.getDescription(), new LinkedHashSet<>(t.getParams())))
                .toList();

        RetryPolicy retry = null;
        if (properties.getRetry() != null) {
            retry = new RetryPolicy(properties.getRetry().getMaxAttempts(), properties.getRetry().getBackoffMs());
        }

        SubMachineBinding subBinding = null;
        if (properties.getSub() != null) {
            subBinding = convertSub(name, properties.getSub());
        }

        List<io.github.biglv666.statekit.define.TimerSpec> timers = properties.getTimers().stream()
                .map(t -> new io.github.biglv666.statekit.define.TimerSpec(
                        t.getFrom(), t.getAfter(), t.getEvent(), t.getSinceColumn()))
                .toList();

        return new MachineDefinition(name, properties.getStateType(),
                properties.getIdType() == null ? Long.class : properties.getIdType(),
                properties.getTable(), properties.getStatusColumn(), properties.getIdColumn(),
                strategy, transitions,
                properties.getVersionColumn(), retry, subBinding, properties.isReactive(),
                properties.getCompensation(), timers);
    }

    private static SubMachineBinding convertSub(String name, MachineProperties.SubProperties sub) {
        SubMachineBinding.Strategy strategy;
        try {
            strategy = SubMachineBinding.Strategy.valueOf(sub.getStrategy().toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("状态机 [%s] 的 sub.strategy 非法: [%s]，只支持 all / any / count"
                    .formatted(name, sub.getStrategy()), e);
        }
        return new SubMachineBinding(sub.getParent(), sub.getParentState(), sub.getGroupColumn(),
                strategy, sub.getCount(), sub.getOnCompleteEvent());
    }
}
