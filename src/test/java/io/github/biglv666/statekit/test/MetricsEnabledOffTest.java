package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.FireArg;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.metrics.FireMetrics;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.testapp.TestApplication;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.4.0 指标降级路径 B：容器有 MeterRegistry 但 {@code state-kit.metrics.enabled=false}
 * → FireMetrics 强制 NOOP，registry 上不产生任何 statekit 指标。
 */
@SpringBootTest(classes = TestApplication.class, properties = "state-kit.metrics.enabled=false")
class MetricsEnabledOffTest {

    @TestConfiguration
    static class MeterConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    @Qualifier("order")
    StateMachine<OrderStatus, Long> order;

    @Autowired
    FireMetrics fireMetrics;

    @Autowired
    MeterRegistry registry;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void enabled关闭时强制NOOP且不产生指标() {
        assertThat(fireMetrics).isSameAs(FireMetrics.NOOP);

        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");
        order.fire(1L, "PAY", FireArg.set("pay_no", "T-1"));

        assertThat(order.currentState(1L)).contains(OrderStatus.PAID);
        assertThat(registry.find("statekit.fire").timers()).isEmpty();
        assertThat(registry.find("statekit.fire.retries").counters()).isEmpty();
        assertThat(registry.find("statekit.compensation").counters()).isEmpty();
    }
}
