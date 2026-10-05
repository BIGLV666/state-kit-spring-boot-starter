package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.FireArg;
import io.github.biglv666.statekit.ReactiveStateMachine;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.config.StateKitAutoConfiguration;
import io.github.biglv666.statekit.define.MachineDefinition;
import io.github.biglv666.statekit.exception.StateConflictException;
import io.github.biglv666.statekit.metrics.FireMetrics;
import io.github.biglv666.statekit.testapp.ReactiveTestStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.4.0 Reactive 通道 fire 指标测试：无 retry/补偿（通道现状不支持），
 * outcome 覆盖 success / illegal / guard_rejected / conflict / conflict_log 五种。
 *
 * <p>CAS 冲突窗口构造：守卫（读态之后、CAS 之前）经独立 R2DBC 连接把状态改为
 * PAID 并放行——等价于并发写者在读态与 CAS 之间提交，主 CAS 的 WHERE 重评不命中。</p>
 */
class ReactiveMetricsTest {

    @Test
    void reactive五类outcome记录准确() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(R2dbcAutoConfiguration.class,
                        StateKitAutoConfiguration.class))
                .withUserConfiguration(ReactiveDslConfig.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "spring.r2dbc.url=r2dbc:h2:mem:///reactive_metrics;DB_CLOSE_DELAY=-1",
                        "spring.r2dbc.username=sa",
                        "spring.r2dbc.password=");

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            @SuppressWarnings("unchecked")
            ReactiveStateMachine<ReactiveTestStatus, Long> machine =
                    (ReactiveStateMachine<ReactiveTestStatus, Long>) context.getBean("reactiveOrderReactive");
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            DatabaseClient client = context.getBean(DatabaseClient.class);
            client.sql("CREATE TABLE IF NOT EXISTS t_reactive (id BIGINT PRIMARY KEY, status VARCHAR(32))")
                    .fetch().rowsUpdated().block();
            client.sql("CREATE TABLE IF NOT EXISTS t_reactive_g (id BIGINT PRIMARY KEY, status VARCHAR(32))")
                    .fetch().rowsUpdated().block();
            client.sql("CREATE TABLE IF NOT EXISTS t_reactive_l (id BIGINT PRIMARY KEY, status VARCHAR(32))")
                    .fetch().rowsUpdated().block();
            client.sql("DELETE FROM t_reactive").fetch().rowsUpdated().block();
            client.sql("DELETE FROM t_reactive_g").fetch().rowsUpdated().block();
            client.sql("DELETE FROM t_reactive_l").fetch().rowsUpdated().block();

            // success
            client.sql("INSERT INTO t_reactive (id, status) VALUES (1, 'CREATED')")
                    .fetch().rowsUpdated().block();
            machine.fire(1L, "PAY").block();
            Timer success = registry.find("statekit.fire")
                    .tags("machine", "reactiveOrder", "event", "PAY",
                            "from", "CREATED", "to", "PAID", "outcome", FireMetrics.OUTCOME_SUCCESS)
                    .timer();
            assertThat(success).isNotNull();
            assertThat(success.count()).isEqualTo(1);

            // illegal：PAID 无 PAY 出边
            try {
                machine.fire(1L, "PAY").block();
            } catch (Exception ignored) {
            }
            Timer illegal = registry.find("statekit.fire")
                    .tags("machine", "reactiveOrder", "event", "PAY",
                            "from", "PAID", "to", "-", "outcome", FireMetrics.OUTCOME_ILLEGAL)
                    .timer();
            assertThat(illegal).isNotNull();
            assertThat(illegal.count()).isEqualTo(1);

            // conflict：守卫内模拟并发写（状态在 CAS 前被改为 PAID）
            client.sql("INSERT INTO t_reactive (id, status) VALUES (2, 'CREATED')")
                    .fetch().rowsUpdated().block();
            try {
                machine.fire(2L, "PAY", FireArg.param("simulateConcurrentWrite", Boolean.TRUE)).block();
            } catch (StateConflictException ignored) {
            }
            Timer conflict = registry.find("statekit.fire")
                    .tags("machine", "reactiveOrder", "event", "PAY",
                            "from", "CREATED", "to", "-", "outcome", FireMetrics.OUTCOME_CONFLICT)
                    .timer();
            assertThat(conflict).isNotNull();
            assertThat(conflict.count()).isEqualTo(1);

            // guard_rejected：param reject=true 时守卫拒绝
            @SuppressWarnings("unchecked")
            ReactiveStateMachine<ReactiveTestStatus, Long> guarded =
                    (ReactiveStateMachine<ReactiveTestStatus, Long>) context.getBean("reactiveGuardedReactive");
            client.sql("INSERT INTO t_reactive_g (id, status) VALUES (1, 'CREATED')")
                    .fetch().rowsUpdated().block();
            try {
                guarded.fire(1L, "GO", FireArg.param("reject", Boolean.TRUE)).block();
            } catch (Exception ignored) {
            }
            Timer rejected = registry.find("statekit.fire")
                    .tags("machine", "reactiveGuarded", "event", "GO",
                            "from", "CREATED", "to", "-", "outcome", FireMetrics.OUTCOME_GUARD_REJECTED)
                    .timer();
            assertThat(rejected).isNotNull();
            assertThat(rejected.count()).isEqualTo(1);

            // conflict_log：守卫内模拟并发写 + log 策略 → CAS 未命中不抛异常
            @SuppressWarnings("unchecked")
            ReactiveStateMachine<ReactiveTestStatus, Long> logging =
                    (ReactiveStateMachine<ReactiveTestStatus, Long>) context.getBean("reactiveLoggingReactive");
            client.sql("INSERT INTO t_reactive_l (id, status) VALUES (1, 'CREATED')")
                    .fetch().rowsUpdated().block();
            logging.fire(1L, "PAY", FireArg.param("simulateConcurrentWrite", Boolean.TRUE)).block();
            Timer conflictLog = registry.find("statekit.fire")
                    .tags("machine", "reactiveLogging", "event", "PAY",
                            "from", "CREATED", "to", "-", "outcome", FireMetrics.OUTCOME_CONFLICT_LOG)
                    .timer();
            assertThat(conflictLog).isNotNull();
            assertThat(conflictLog.count()).isEqualTo(1);
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
                    .guard("reactiveConflictGuard")
                    .build();
        }

        /** 恒拒绝守卫（param reject=true 时）：验证 guard_rejected 归因 */
        @Bean
        MachineDefinition reactiveGuarded() {
            return StateMachine.define("reactiveGuarded", ReactiveTestStatus.class)
                    .table("t_reactive_g", "status", "id")
                    .reactive()
                    .transition(ReactiveTestStatus.CREATED, ReactiveTestStatus.PAID, "GO")
                    .guard("reactiveRejectGuard")
                    .build();
        }

        /** log 冲突策略：验证 conflict_log 归因 */
        @Bean
        MachineDefinition reactiveLogging() {
            return StateMachine.define("reactiveLogging", ReactiveTestStatus.class)
                    .table("t_reactive_l", "status", "id")
                    .reactive()
                    .conflictStrategy(io.github.biglv666.statekit.ConflictStrategy.LOG)
                    .transition(ReactiveTestStatus.CREATED, ReactiveTestStatus.PAID, "PAY")
                    .guard("reactiveLoggingConflictGuard")
                    .build();
        }

        /**
         * 竞态窗口守卫（仅测试用，t_reactive）：param 携带 simulateConcurrentWrite=true 时，
         * 经独立连接把本实体状态改为 PAID（模拟并发写者已提交），随后放行——
         * 主 fire 的 CAS 将按 WHERE status=CREATED 重评不命中。
         */
        @Bean
        io.github.biglv666.statekit.ReactiveStateGuard<ReactiveTestStatus, Long> reactiveConflictGuard(
                DatabaseClient client) {
            return tx -> {
                if (!Boolean.TRUE.equals(tx.param("simulateConcurrentWrite", Boolean.class))) {
                    return Mono.just(true);
                }
                return client.sql("UPDATE t_reactive SET status = 'PAID' WHERE id = :id")
                        .bind("id", tx.entityId())
                        .fetch().rowsUpdated()
                        .then(Mono.just(true));
            };
        }

        /** 同上，作用于 t_reactive_l */
        @Bean
        io.github.biglv666.statekit.ReactiveStateGuard<ReactiveTestStatus, Long> reactiveLoggingConflictGuard(
                DatabaseClient client) {
            return tx -> {
                if (!Boolean.TRUE.equals(tx.param("simulateConcurrentWrite", Boolean.class))) {
                    return Mono.just(true);
                }
                return client.sql("UPDATE t_reactive_l SET status = 'PAID' WHERE id = :id")
                        .bind("id", tx.entityId())
                        .fetch().rowsUpdated()
                        .then(Mono.just(true));
            };
        }

        @Bean
        io.github.biglv666.statekit.ReactiveStateGuard<ReactiveTestStatus, Long> reactiveRejectGuard() {
            return tx -> Mono.just(!Boolean.TRUE.equals(tx.param("reject", Boolean.class)));
        }
    }
}
