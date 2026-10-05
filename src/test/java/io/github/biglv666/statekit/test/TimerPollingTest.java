package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.testapp.TestApplication;
import io.github.biglv666.statekit.timer.TimerScanner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.5.0 内置轮询开启场景：polling-enabled=true + 短间隔，
 * 到期实体在无人干预下被自动推进（SmartLifecycle 生命周期生效）。
 * 独立 properties → 独立测试上下文。
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "state-kit.timers.polling-enabled=true",
        "state-kit.timers.poll-interval=200ms",
        "state-kit.timers.batch-size=100"
})
class TimerPollingTest {

    @Autowired
    @Qualifier("timerOrder")
    StateMachine<OrderStatus, Long> timerOrder;

    @Autowired
    TimerScanner timerScanner;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 轮询开启时到期实体被周期性自动推进() throws Exception {
        assertThat(timerScanner.isRunning()).isTrue();
        jdbcTemplate.update("DELETE FROM t_timer_order WHERE id = 777");
        jdbcTemplate.update("INSERT INTO t_timer_order (id, status, create_time) VALUES (777, 'CREATED', ?)",
                Timestamp.from(Instant.now().minusSeconds(60 * 60)));

        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (timerOrder.currentState(777L).orElse(null) == OrderStatus.CANCELLED) {
                return;   // 轮询已自动推进
            }
            Thread.sleep(100);
        }
        throw new AssertionError("5s 内轮询未自动推进到期实体");
    }
}
