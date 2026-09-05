package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.FireArg;
import io.github.biglv666.statekit.ReactiveStateAction;
import io.github.biglv666.statekit.ReactiveStateGuard;
import io.github.biglv666.statekit.ReactiveStateMachine;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.config.StateKitAutoConfiguration;
import io.github.biglv666.statekit.define.MachineDefinition;
import io.github.biglv666.statekit.exception.IllegalTransitionException;
import io.github.biglv666.statekit.testapp.ReactiveTestStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 0.2.0 Reactive 通道测试：R2DBC H2 全流程（fire/守卫/动作/查询/tryFire）。
 * 机器经 Java DSL 声明 reactive=true，阻塞 Bean 为懒加载，纯 R2DBC 环境可正常启动。
 */
class ReactiveMachineTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    R2dbcAutoConfiguration.class,
                    StateKitAutoConfiguration.class))
            .withUserConfiguration(ReactiveDslConfig.class)
            .withPropertyValues(
                    "spring.r2dbc.url=r2dbc:h2:mem:///reactive_test;DB_CLOSE_DELAY=-1",
                    "spring.r2dbc.username=sa",
                    "spring.r2dbc.password=");

    @Test
    void reactive状态机全流程() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var machine = (ReactiveStateMachine<ReactiveTestStatus, Long>) context.getBean("reactiveOrderReactive");

            DatabaseClient client = context.getBean(DatabaseClient.class);
            client.sql("CREATE TABLE IF NOT EXISTS t_reactive (id BIGINT PRIMARY KEY, status VARCHAR(32), marker VARCHAR(64))")
                    .fetch().rowsUpdated().block();
            client.sql("DELETE FROM t_reactive").fetch().rowsUpdated().block();
            client.sql("INSERT INTO t_reactive (id, status) VALUES (1, 'CREATED')")
                    .fetch().rowsUpdated().block();

            // currentState / nextStates
            assertThat(machine.currentState(1L).block()).isEqualTo(ReactiveTestStatus.CREATED);
            assertThat(machine.nextStates(1L).block()).containsExactly(ReactiveTestStatus.PAID);

            // fire：守卫通过 → 动作经 R2DBC 写 marker → 状态推进
            machine.fire(1L, "PAY", FireArg.set("marker", "paid-ok")).block();
            assertThat(machine.currentState(1L).block()).isEqualTo(ReactiveTestStatus.PAID);
            String marker = client.sql("SELECT marker FROM t_reactive WHERE id = 1")
                    .map(row -> row.get("marker", String.class)).first().block();
            assertThat(marker).isEqualTo("paid-ok");

            // 终态不可再 fire：IllegalTransition 以 error 传播
            assertThat(machine.isFinal(1L).block()).isTrue();
            assertThatThrownBy(() -> machine.fire(1L, "PAY").block())
                    .isInstanceOf(IllegalTransitionException.class);

            // 实体不存在 → 非法流转（确定性错误不静默）
            assertThatThrownBy(() -> machine.tryFire(99L, "PAY").block())
                    .isInstanceOf(IllegalTransitionException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ReactiveDslConfig {

        @Bean
        MachineDefinition reactiveOrder() {
            return StateMachine.define("reactiveOrder", ReactiveTestStatus.class)
                    .table("t_reactive", "status", "id")
                    .reactive()
                    .transition(ReactiveTestStatus.CREATED, ReactiveTestStatus.PAID, "PAY")
                    .guard("reactivePayGuard")
                    .action("reactivePayAction")
                    .build();
        }

        @Bean
        ReactiveStateGuard<ReactiveTestStatus, Long> reactivePayGuard() {
            return tx -> Mono.just(true);
        }

        @Bean
        ReactiveStateAction<ReactiveTestStatus, Long> reactivePayAction(DatabaseClient client) {
            return tx -> client.sql("UPDATE t_reactive SET marker = :marker WHERE id = :id")
                    .bind("marker", "paid-ok")
                    .bind("id", tx.entityId())
                    .fetch().rowsUpdated().then();
        }
    }
}
