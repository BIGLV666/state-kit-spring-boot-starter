package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.core.DefaultStateMachine;
import io.github.biglv666.statekit.core.MachineRuntime;
import io.github.biglv666.statekit.core.TransitionRouter;
import io.github.biglv666.statekit.define.MachineDefinition;
import io.github.biglv666.statekit.metrics.FireMetrics;
import io.github.biglv666.statekit.store.JdbcStateStore;
import io.github.biglv666.statekit.testapp.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.4.0 指标降级路径测试：FireMetrics 为 null（micrometer 不在类路径时 Registrar
 * 的注入结果）→ 构造器兜底 NOOP，fire 行为与 0.3.0 完全一致。
 *
 * <p>说明：Spring 环境下只要 micrometer-core 在类路径，Boot 必定装配（哪怕是 no-op）
 * 的 MeterRegistry，"容器无 registry"场景无法在单模块内构造；类路径缺失场景由
 * {@code @ConditionalOnClass} 条件保证（Boot 标准机制），故此处以单元级构造验证兜底。</p>
 */
class MetricsDisabledTest {

    @Test
    void 构造器metrics为null时兜底NOOP且fire行为不变() {
        MachineDefinition definition = StateMachine.define("unitOrder", OrderStatus.class)
                .table("t_order", "status", "id")
                .transition(OrderStatus.CREATED, OrderStatus.PAID, "PAY")
                .build();
        TransitionRouter router = new TransitionRouter();
        router.compile(definition.getTransitions());
        MachineRuntime<OrderStatus> runtime = new MachineRuntime<>(definition, OrderStatus.class, router);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:unit_metrics;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS t_order (id BIGINT PRIMARY KEY, status VARCHAR(32))");
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");

        // 8 参旧构造器：Registrar 在 FireMetrics Bean 缺失时等价于传 null
        DefaultStateMachine<OrderStatus, Long> machine = new DefaultStateMachine<>(
                runtime, new JdbcStateStore(jdbcTemplate, "t_order", "status", "id"),
                null, null, null, null, event -> { }, null);

        assertThat(ReflectionTestUtils.getField(machine, "metrics")).isSameAs(FireMetrics.NOOP);

        // fire 行为与 0.3.0 一致：CAS 成功、查询正常（本机仅 PAY 边，PAID 无出边）
        machine.fire(1L, "PAY");
        assertThat(machine.currentState(1L)).contains(OrderStatus.PAID);
        assertThat(machine.availableActions(1L)).isEmpty();
    }
}
