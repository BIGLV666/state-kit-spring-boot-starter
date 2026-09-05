package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.config.StateKitAutoConfiguration;
import io.github.biglv666.statekit.define.MachineDefinition;
import io.github.biglv666.statekit.testapp.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动期 fail-fast 校验：每种错误形态都在启动阶段暴露，错误信息定位到 machine + 流转。
 */
class StartupValidationTest {

    private static final String PREFIX = "state-kit.machines.order.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    StateKitAutoConfiguration.class));

    /** 正常声明（作为各错误用例的基线） */
    private String[] okProps() {
        return new String[]{
                PREFIX + "state-type=" + OrderStatus.class.getName(),
                PREFIX + "table=t_order",
                PREFIX + "transitions[0].from=CREATED",
                PREFIX + "transitions[0].event=PAY",
                PREFIX + "transitions[0].to=PAID"
        };
    }

    private ContextConsumer<AssertableApplicationContext> failureContaining(String... fragments) {
        return context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class);
            for (String fragment : fragments) {
                assertThat(context.getStartupFailure().getMessage()).contains(fragment);
            }
        };
    }

    @Test
    void to状态不在枚举内() {
        runner.withPropertyValues(okProps())
                .withPropertyValues(PREFIX + "transitions[0].to=NOT_IN_ENUM")
                .run(failureContaining("order", "NOT_IN_ENUM"));
    }

    @Test
    void from状态不在枚举内() {
        runner.withPropertyValues(okProps())
                .withPropertyValues(PREFIX + "transitions[0].from=GHOST")
                .run(failureContaining("order", "GHOST"));
    }

    @Test
    void 同一from与event重复定义() {
        runner.withPropertyValues(okProps())
                .withPropertyValues(
                        PREFIX + "transitions[1].from=CREATED",
                        PREFIX + "transitions[1].event=PAY",
                        PREFIX + "transitions[1].to=SHIPPED")
                .run(failureContaining("order", "重复"));
    }

    @Test
    void 缺少业务表声明() {
        runner.withPropertyValues(
                        PREFIX + "state-type=" + OrderStatus.class.getName(),
                        PREFIX + "transitions[0].from=CREATED",
                        PREFIX + "transitions[0].event=PAY",
                        PREFIX + "transitions[0].to=PAID")
                .run(failureContaining("order", "table"));
    }

    @Test
    void 缺少stateType声明() {
        runner.withPropertyValues(
                        PREFIX + "table=t_order",
                        PREFIX + "transitions[0].from=CREATED",
                        PREFIX + "transitions[0].event=PAY",
                        PREFIX + "transitions[0].to=PAID")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 表名非法SQL标识符被拒() {
        runner.withPropertyValues(okProps())
                .withPropertyValues(PREFIX + "table=t_order; DROP TABLE t_order")
                .run(failureContaining("order", "table", "标识符"));
    }

    @Test
    void 引用不存在的actionBean() {
        runner.withPropertyValues(okProps())
                .withPropertyValues(PREFIX + "transitions[0].action=noSuchAction")
                .run(failureContaining("order", "noSuchAction", "不存在"));
    }

    @Test
    void 引用类型错误的guardBean() {
        runner.withUserConfiguration(WrongHookConfig.class)
                .withPropertyValues(okProps())
                .withPropertyValues(PREFIX + "transitions[0].guard=notAGuard")
                .run(failureContaining("order", "notAGuard", "StateGuard"));
    }

    @Test
    void 状态机名与JavaDSL重复() {
        runner.withUserConfiguration(DslConfig.class)
                .withPropertyValues(okProps())
                .run(failureContaining("order", "重复"));
    }

    @Test
    void 无任何流转声明() {
        runner.withPropertyValues(
                        PREFIX + "state-type=" + OrderStatus.class.getName(),
                        PREFIX + "table=t_order")
                .run(failureContaining("order", "transitions"));
    }

    @Test
    void 合法声明成功启动且泛型Bean可注入() {
        runner.withPropertyValues(okProps()).run(context -> {
            assertThat(context).hasNotFailed();
            Object machine = context.getBean("order");
            assertThat(machine).isInstanceOf(StateMachine.class);
            // DSL 定义 bean 不再残留：容器内 "order" 就是状态机本身
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class WrongHookConfig {
        @Bean
        String notAGuard() {
            return "not a guard";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DslConfig {
        @Bean
        MachineDefinition order() {
            return StateMachine.define("order", OrderStatus.class)
                    .table("t_order", "status", "id")
                    .transition(OrderStatus.CREATED, OrderStatus.PAID, "PAY")
                    .build();
        }
    }
}
