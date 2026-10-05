package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.event.StateTransitedEvent;
import io.github.biglv666.statekit.metrics.FireMetrics;
import io.github.biglv666.statekit.testapp.FlowStatus;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.testapp.TestApplication;
import io.github.biglv666.statekit.testapp.TestEventCollector;
import io.github.biglv666.statekit.testapp.TestHooksConfig;
import io.github.biglv666.statekit.timer.TimerScanner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.5.0 停留超时自动流转测试：到期触发走完整 fire 语义（守卫/CAS/动作/事件），
 * 边界（未到期/恰好到期/已推进）、守卫拒绝重试、String 主键、批量分批、
 * 装配边界与扫描指标。轮询在测试 yml 中关闭，统一手动 scanOnce。
 */
@SpringBootTest(classes = TestApplication.class)
class TimerMachineTest {

    @TestConfiguration
    static class MeterConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    @Qualifier("timerOrder")
    StateMachine<OrderStatus, Long> timerOrder;

    @Autowired
    @Qualifier("timerStr")
    StateMachine<FlowStatus, String> timerStr;

    @Autowired
    @Qualifier("timerG")
    StateMachine<FlowStatus, Long> timerG;

    @Autowired
    TimerScanner timerScanner;

    @Autowired
    MeterRegistry registry;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TestEventCollector eventCollector;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_timer_order");
        jdbcTemplate.update("DELETE FROM t_timer_str");
        jdbcTemplate.update("DELETE FROM t_timer_g");
        eventCollector.reset();
        TestHooksConfig.GateHolder.closed.set(false);
        registry.clear();
    }

    private void seedExpired(String table, Object id) {
        // t_timer_str / t_timer_g 用 FlowStatus（初始态 A），其余用 OrderStatus（初始态 CREATED）
        String initial = "t_timer_str".equals(table) || "t_timer_g".equals(table) ? "A" : "CREATED";
        jdbcTemplate.update("INSERT INTO " + table + " (id, status, create_time) VALUES (?, ?, ?)",
                id, initial, Timestamp.from(Instant.now().minusSeconds(60 * 31)));
    }

    @Test
    void 到期实体被自动流转且动作与事件完整执行() {
        seedExpired("t_timer_order", 1L);

        int fired = timerScanner.scanOnce();

        assertThat(fired).isEqualTo(1);
        assertThat(timerOrder.currentState(1L)).contains(OrderStatus.CANCELLED);
        // 动作确实执行（CAS 之后、同事务）
        String note = jdbcTemplate.queryForObject(
                "SELECT note FROM t_timer_order WHERE id = 1", String.class);
        assertThat(note).isEqualTo("timer-cancelled");
        // StateTransitedEvent 正常发布
        List<StateTransitedEvent> events = eventCollector.events.stream()
                .filter(e -> "timerOrder".equals(e.getMachine()) && "CANCEL".equals(e.getEvent()))
                .toList();
        assertThat(events).hasSize(1);
    }

    @Test
    void 未到期不触发_恰好到期边界命中() {
        // 未到期：create_time = now - 29m（阈值 30m）
        jdbcTemplate.update("INSERT INTO t_timer_order (id, status, create_time) VALUES (1, 'CREATED', ?)",
                Timestamp.from(Instant.now().minusSeconds(60 * 29)));
        // 恰好到期：create_time 略早于 now - 30m（插入到扫描有时间流逝，边界按 <= 判定）
        jdbcTemplate.update("INSERT INTO t_timer_order (id, status, create_time) VALUES (2, 'CREATED', ?)",
                Timestamp.from(Instant.now().minusSeconds(60 * 30)));

        int fired = timerScanner.scanOnce();

        assertThat(fired).isEqualTo(1);
        assertThat(timerOrder.currentState(1L)).contains(OrderStatus.CREATED);
        assertThat(timerOrder.currentState(2L)).contains(OrderStatus.CANCELLED);
    }

    @Test
    void 状态已推进的实体不再触发() {
        seedExpired("t_timer_order", 1L);
        timerOrder.fire(1L, "PAY");   // 业务先行推进到 PAID

        int fired = timerScanner.scanOnce();

        assertThat(fired).isZero();
        assertThat(timerOrder.currentState(1L)).contains(OrderStatus.PAID);
        // 只有业务 fire 的 PAY 事件，无扫描触发的 CANCEL 事件
        assertThat(eventCollector.events.stream()
                .filter(e -> "CANCEL".equals(e.getEvent()))).isEmpty();
    }

    @Test
    void 守卫拒绝后下轮重试_条件满足后自动推进() {
        seedExpired("t_timer_g", 1L);
        TestHooksConfig.GateHolder.closed.set(true);

        assertThat(timerScanner.scanOnce()).isZero();
        assertThat(timerG.currentState(1L)).contains(FlowStatus.A);

        TestHooksConfig.GateHolder.closed.set(false);   // 守卫条件满足
        assertThat(timerScanner.scanOnce()).isEqualTo(1);
        assertThat(timerG.currentState(1L)).contains(FlowStatus.B);
    }

    @Test
    void String主键机器正常触发() {
        seedExpired("t_timer_str", "str-1");

        assertThat(timerScanner.scanOnce()).isEqualTo(1);
        assertThat(timerStr.currentState("str-1")).contains(FlowStatus.B);
    }

    @Test
    void 批量到期实体分批扫描无遗漏() {
        for (long id = 1; id <= 150; id++) {
            seedExpired("t_timer_order", id);
        }

        int fired = timerScanner.scanOnce();   // batch-size=50，需 3 批

        assertThat(fired).isEqualTo(150);
        Long remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_timer_order WHERE status = 'CREATED'", Long.class);
        assertThat(remaining).isZero();
    }

    @Test
    void 多轮扫描不重复推进() {
        seedExpired("t_timer_order", 1L);

        assertThat(timerScanner.scanOnce()).isEqualTo(1);
        assertThat(timerScanner.scanOnce()).isZero();   // 已推进，后续轮次零触发
        assertThat(eventCollector.events.stream()
                .filter(e -> "timerOrder".equals(e.getMachine()))).hasSize(1);
    }

    @Test
    void 扫描指标记录次数与推进数() {
        seedExpired("t_timer_order", 1L);
        seedExpired("t_timer_order", 2L);

        timerScanner.scanOnce();

        // statekit.timer.scan：每 (machine, timer) 一条记录
        var scanTimer = registry.find("statekit.timer.scan")
                .tags("machine", "timerOrder", "timer", "CREATED@CANCEL").timer();
        assertThat(scanTimer).isNotNull();
        assertThat(scanTimer.count()).isEqualTo(1);
        // statekit.timer.fired：推进数
        var firedCounter = registry.find("statekit.timer.fired")
                .tags("machine", "timerOrder", "timer", "CREATED@CANCEL").counter();
        assertThat(firedCounter).isNotNull();
        assertThat(firedCounter.count()).isEqualTo(2);
    }

    @Test
    void 无timers声明时不装配扫描器() {
        // order 机器（无 timers）的上下文里，scanner 计划只覆盖声明了 timers 的机器
        assertThat(timerScanner.hasPlans()).isTrue();
        // 其 plans 不含 order/task 等无 timers 机器（通过扫描后 fired=0 间实验证）
        jdbcTemplate.update("DELETE FROM t_order");   // 共享表：清掉其它上下文测试残留的主键
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");
        assertThat(timerScanner.scanOnce()).isZero();
        assertThat(registry.find("statekit.timer.scan").timers())
                .allSatisfy(t -> assertThat(t.getId().getTag("machine"))
                        .isIn("timerOrder", "timerStr", "timerG"));
    }
}
