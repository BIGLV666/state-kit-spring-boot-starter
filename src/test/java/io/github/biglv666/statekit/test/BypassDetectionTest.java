package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.event.StateBypassDetectedEvent;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.2.0 BYPASS 绕改检测测试（mode=event）：拦截绕过 fire 的 status 修改。
 */
@SpringBootTest(classes = TestApplication.class, properties = "state-kit.bypass.mode=event")
@Import(BypassDetectionTest.BypassCollector.class)
class BypassDetectionTest {

    @Autowired
    @Qualifier("order")
    StateMachine<OrderStatus, Long> order;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    BypassCollector collector;

    @Component
    static class BypassCollector {
        final List<StateBypassDetectedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(StateBypassDetectedEvent event) {
            events.add(event);
        }
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("DELETE FROM t_flow");
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");
        collector.events.clear();
    }

    @Test
    void 框架fire不触发BYPASS() {
        order.fire(1L, "PAY");
        assertThat(order.currentState(1L)).contains(OrderStatus.PAID);
        assertThat(collector.events).isEmpty();
    }

    @Test
    void 绕过fire改status触发BYPASS事件() {
        jdbcTemplate.update("UPDATE t_order SET status = 'PAID' WHERE id = 1");

        assertThat(collector.events).hasSize(1);
        StateBypassDetectedEvent event = collector.events.get(0);
        assertThat(event.getMachine()).isEqualTo("order");
        assertThat(event.getTable()).isEqualTo("t_order");
        assertThat(event.getEntityId()).isEqualTo(1L);
        assertThat(event.getSql()).containsIgnoringCase("update t_order");
    }

    @Test
    void 不碰status列的更新不触发() {
        jdbcTemplate.update("UPDATE t_order SET pay_no = 'x' WHERE id = 1");
        assertThat(collector.events).isEmpty();
    }

    @Test
    void 非机器表的更新不触发() {
        // t_plain 不是任何状态机的业务表，改它不算 BYPASS
        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS t_plain (id BIGINT PRIMARY KEY, status VARCHAR(32))");
        jdbcTemplate.update("INSERT INTO t_plain (id, status) VALUES (1, 'A')");
        jdbcTemplate.update("UPDATE t_plain SET status = 'B' WHERE id = 1");
        assertThat(collector.events).isEmpty();
    }

    @Test
    void 参数化语句触发但entityId不可解析() {
        jdbcTemplate.update("UPDATE t_order SET status = ? WHERE id = ?", "PAID", 1L);
        assertThat(collector.events).hasSize(1);
        assertThat(collector.events.get(0).getEntityId()).isNull();
        // 状态确实被改了：检测是旁路观测，不阻断
        assertThat(order.currentState(1L)).contains(OrderStatus.PAID);
    }
}
