package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.config.StateKitAutoConfiguration;
import io.github.biglv666.statekit.define.MachineDefinition;

import io.github.biglv666.statekit.store.StateStore;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.5.0 停留超时声明（timers）的启动期校验：非法 from、事件无出边、时长非正、
 * 列名注入、reactive 互斥、自定义 StateStore 互斥、重复 timer。
 */
class StartupValidationTimerTest {

    private static final String P = "state-kit.machines.order.";

    /**
     * 带 DataSource/事务的完整 runner：合法用例需实例化扫描器（非懒 Bean，实例化期取 DataSource）。
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
                    org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration.class,
                    StateKitAutoConfiguration.class));

    private String[] base() {
        return new String[]{
                P + "state-type=" + OrderStatus.class.getName(),
                P + "table=t_order",
                P + "transitions[0].from=CREATED",
                P + "transitions[0].event=PAY",
                P + "transitions[0].to=PAID",
                P + "timers[0].from=CREATED",
                P + "timers[0].after=30m",
                P + "timers[0].event=PAY",
                P + "timers[0].since-column=create_time"
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
    void 合法声明可通过() {
        runner.withPropertyValues(base()).run(context ->
                assertThat(context).hasNotFailed());
    }

    @Test
    void from不在枚举中() {
        runner.withPropertyValues(base())
                .withPropertyValues(P + "timers[0].from=GHOST")
                .run(failureContaining("order", "GHOST", "不在枚举"));
    }

    @Test
    void 事件在from状态上无出边() {
        runner.withPropertyValues(base())
                .withPropertyValues(P + "timers[0].event=SHIP")
                .run(failureContaining("SHIP", "没有出边"));
    }

    @Test
    void 时长为零或负数() {
        runner.withPropertyValues(base())
                .withPropertyValues(P + "timers[0].after=0s")
                .run(failureContaining("after", "正时长"));
        runner.withPropertyValues(base())
                .withPropertyValues(P + "timers[0].after=-5m")
                .run(failureContaining("after", "正时长"));
    }

    @Test
    void sinceColumnSQL注入() {
        runner.withPropertyValues(base())
                .withPropertyValues(P + "timers[0].since-column=create_time; DROP TABLE t_order")
                .run(failureContaining("since-column", "非法"));
    }

    @Test
    void reactive机器不支持timers() {
        runner.withPropertyValues(base())
                .withPropertyValues(P + "reactive=true")
                .run(failureContaining("timers", "reactive"));
    }

    @Test
    void 重复timer() {
        runner.withPropertyValues(base())
                .withPropertyValues(
                        P + "timers[1].from=CREATED",
                        P + "timers[1].after=1h",
                        P + "timers[1].event=PAY",
                        P + "timers[1].since-column=create_time")
                .run(failureContaining("重复的 timer"));
    }

    @Test
    void 自定义StateStore与timers互斥() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StateKitAutoConfiguration.class))
                .withUserConfiguration(StoreConfig.class)
                .withPropertyValues(base())
                .run(failureContaining("timers", "JDBC 存储"));
    }

    @Configuration(proxyBeanMethods = false)
    static class StoreConfig {
        @Bean
        StateStore customStore() {
            return new StateStore() {
                @Override
                public Optional<String> readState(Object id) {
                    return Optional.empty();
                }

                @Override
                public int casTransition(Object id, String from, String to, java.util.Map<String, Object> setColumns) {
                    return 0;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DslMachines {
        @Bean
        MachineDefinition timerDsl() {
            return StateMachine.define("timerDsl", OrderStatus.class)
                    .table("t_order", "status", "id")
                    .transition(OrderStatus.CREATED, OrderStatus.PAID, "PAY")
                    .timer(OrderStatus.CREATED, "PAY", java.time.Duration.ofMinutes(30), "create_time")
                    .build();
        }
    }

    @Test
    void JavaDSL通道timer声明等效() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration.class,
                        StateKitAutoConfiguration.class))
                .withUserConfiguration(DslMachines.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:timer_dsl;DB_CLOSE_DELAY=-1",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // DSL 定义 bean 被状态机 bean 同名覆盖（设计行为），经 exporter 反查定义
                    var exporter = context.getBean("timerDslExporter",
                            io.github.biglv666.statekit.export.StateMachineExporter.class);
                    MachineDefinition def = exporter.runtime().getDefinition();
                    assertThat(def.getTimers()).hasSize(1);
                    assertThat(def.getTimers().get(0).from()).isEqualTo("CREATED");
                    assertThat(def.getTimers().get(0).after()).isEqualTo(java.time.Duration.ofMinutes(30));
                    assertThat(def.getTimers().get(0).event()).isEqualTo("PAY");
                    assertThat(def.getTimers().get(0).sinceColumn()).isEqualTo("create_time");
                });
    }
}
