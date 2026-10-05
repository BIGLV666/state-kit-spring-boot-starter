package io.github.biglv666.statekit.config;

import io.github.biglv666.statekit.export.StateMachineExporter;
import io.github.biglv666.statekit.metrics.StateKitEndpoint;
import io.github.biglv666.statekit.metrics.StateKitHealthIndicator;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * state-kit 观测自动装配（0.4.0+）：健康指示器与 Actuator 端点。
 *
 * <p>整类由类级 {@code @ConditionalOnClass} 守卫——类路径无 actuator 时本配置
 * 连同 {@code StateKitHealthIndicator} / {@code StateKitEndpoint} 一并不加载，
 * 核心功能零影响。健康开关随 Boot 标准 {@code management.health.statekit.enabled}，
 * 端点暴露随 {@code management.endpoints.web.exposure.include}。</p>
 */
@AutoConfiguration(after = StateKitAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.Health")
public class StateKitObservabilityAutoConfiguration {

    /**
     * 健康指示器：上报机器清单与模块装配状态（状态恒 UP，fail-fast 保证启动时已校验）。
     */
    @Bean
    @ConditionalOnEnabledHealthIndicator("statekit")
    @ConditionalOnMissingBean(StateKitHealthIndicator.class)
    public StateKitHealthIndicator stateKitHealthIndicator(ApplicationContext applicationContext,
                                                           StateKitProperties properties) {
        return new StateKitHealthIndicator(applicationContext, properties);
    }

    /**
     * Actuator 端点 {@code statekit}：机器摘要 + 单机详情（含 Mermaid 图）。
     * exporter 按 bean 名（machine名 + Exporter）反查机器名，与 0.3.0 REST 端点同模式；
     * HTTP 暴露由 {@code management.endpoints.web.exposure.include} 控制，
     * 未暴露时 Bean 存在但不被映射。业务方自建同名端点时 {@code @ConditionalOnMissingBean} 避让。
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnMissingBean(StateKitEndpoint.class)
    public StateKitEndpoint stateKitEndpoint(ApplicationContext applicationContext) {
        Map<String, StateMachineExporter> byMachine = new LinkedHashMap<>();
        for (String beanName : applicationContext.getBeanNamesForType(StateMachineExporter.class)) {
            if (beanName.endsWith("Exporter")) {
                String machine = beanName.substring(0, beanName.length() - "Exporter".length());
                byMachine.put(machine, applicationContext.getBean(beanName, StateMachineExporter.class));
            }
        }
        return new StateKitEndpoint(byMachine);
    }
}
