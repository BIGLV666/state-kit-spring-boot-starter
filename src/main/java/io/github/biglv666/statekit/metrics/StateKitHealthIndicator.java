package io.github.biglv666.statekit.metrics;

import io.github.biglv666.statekit.ReactiveStateMachine;
import io.github.biglv666.statekit.config.StateKitProperties;
import io.github.biglv666.statekit.export.StateMachineExporter;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;

/**
 * state-kit 健康指示器（0.4.0+）：上报机器清单与各模块装配状态。
 * 本框架启动期 fail-fast 校验通过才会运行到此处，因此状态恒为 UP，
 * details 用于运维确认当前生效的机器与模块开关：
 *
 * <pre>{@code
 * "stateKit": {
 *     "machines": ["order", "task", "flow"],
 *     "machineCount": 3,
 *     "reactiveMachineCount": 0,
 *     "history": "disabled",
 *     "bypass": "off",
 *     "metrics": "enabled"
 * }
 * }</pre>
 *
 * <p>开关随 Boot 标准 {@code management.health.statekit.enabled=false} 关闭。
 * 类路径无 actuator 时本类不被加载（见观测自动装配的类级守卫）。</p>
 */
public class StateKitHealthIndicator implements HealthIndicator {

    private final ApplicationContext applicationContext;
    private final StateKitProperties properties;

    public StateKitHealthIndicator(ApplicationContext applicationContext, StateKitProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties;
    }

    @Override
    public Health health() {
        List<String> machines = machineNames();
        int reactiveCount = applicationContext
                .getBeanNamesForType(ReactiveStateMachine.class, false, false).length;
        return Health.up()
                .withDetail("machines", machines)
                .withDetail("machineCount", machines.size())
                .withDetail("reactiveMachineCount", reactiveCount)
                .withDetail("history", properties.getHistory().isEnabled() ? "enabled" : "disabled")
                .withDetail("bypass", properties.getBypass().getMode())
                .withDetail("metrics", properties.getMetrics().isEnabled() ? "enabled" : "disabled")
                .build();
    }

    /** 机器名精确反查：exporter bean 名 = machine名 + "Exporter"，仅框架注册的机器有 */
    private List<String> machineNames() {
        List<String> names = new ArrayList<>();
        for (String beanName : applicationContext
                .getBeanNamesForType(StateMachineExporter.class, false, false)) {
            if (beanName.endsWith("Exporter")) {
                names.add(beanName.substring(0, beanName.length() - "Exporter".length()));
            }
        }
        return names;
    }
}
