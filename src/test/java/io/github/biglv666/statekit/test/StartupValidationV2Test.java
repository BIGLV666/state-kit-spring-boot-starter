package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.config.StateKitAutoConfiguration;
import io.github.biglv666.statekit.define.MachineDefinition;
import io.github.biglv666.statekit.define.SubMachineBinding;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.2.0 新增配置的启动期校验：嵌套绑定、retry、version-column、reactive。
 */
class StartupValidationV2Test {

    private static final String P = "state-kit.machines.order.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StateKitAutoConfiguration.class));

    private String[] base() {
        return new String[]{
                P + "state-type=" + OrderStatus.class.getName(),
                P + "table=t_order",
                P + "transitions[0].from=CREATED",
                P + "transitions[0].event=PAY",
                P + "transitions[0].to=PAID"
        };
    }

    private org.springframework.boot.test.context.runner.ContextConsumer<org.springframework.boot.test.context.assertj.AssertableApplicationContext> failureContaining(
            String... fragments) {
        return context -> {
            assertThat(context).hasFailed();
            for (String fragment : fragments) {
                assertThat(context.getStartupFailure().getMessage()).contains(fragment);
            }
        };
    }

    @Test
    void 子机器父机器不存在() {
        runner.withPropertyValues(base())
                .withPropertyValues(
                        P + "sub.parent=ghost",
                        P + "sub.parent-state=PAID",
                        P + "sub.group-column=order_id",
                        P + "sub.strategy=all",
                        P + "sub.on-complete-event=PAY")
                .run(failureContaining("order", "ghost"));
    }

    @Test
    void 子机器父状态不在父枚举中() {
        runner.withPropertyValues(base())
                .withPropertyValues(
                        P + "sub.parent=order",
                        P + "sub.parent-state=NOPE",
                        P + "sub.group-column=order_id",
                        P + "sub.on-complete-event=PAY")
                .run(failureContaining("order", "NOPE"));
    }

    @Test
    void 回发事件在父状态上无出边() {
        runner.withPropertyValues(base())
                .withPropertyValues(
                        P + "sub.parent=order",
                        P + "sub.parent-state=PAID",
                        P + "sub.group-column=order_id",
                        P + "sub.on-complete-event=NOT_AN_EVENT")
                .run(failureContaining("order", "NOT_AN_EVENT"));
    }

    @Test
    void 嵌套与reactive互斥() {
        runner.withPropertyValues(base())
                .withPropertyValues(
                        P + "sub.parent=order",
                        P + "sub.parent-state=PAID",
                        P + "sub.group-column=order_id",
                        P + "sub.on-complete-event=PAY",
                        P + "reactive=true")
                .run(failureContaining("order", "reactive"));
    }

    @Test
    void retry与log策略不兼容() {
        runner.withPropertyValues(base())
                .withPropertyValues(
                        P + "conflict-strategy=log",
                        P + "retry.max-attempts=3")
                .run(failureContaining("order", "retry", "throw"));
    }

    @Test
    void version列名非法被拒() {
        runner.withPropertyValues(base())
                .withPropertyValues(P + "version-column=ver; drop table x")
                .run(failureContaining("order", "version-column", "标识符"));
    }

    @Test
    void count策略阈值为正() {
        runner.withUserConfiguration(DslConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage()).contains("count");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class DslConfig {
        @Bean
        MachineDefinition countZero() {
            return StateMachine.define("countZero", OrderStatus.class)
                    .table("t_order", "status", "id")
                    .subMachineOf("countZero", OrderStatus.CREATED, "order_id",
                            SubMachineBinding.Strategy.COUNT, 0, "PAY")
                    .transition(OrderStatus.CREATED, OrderStatus.PAID, "PAY")
                    .build();
        }
    }
}
