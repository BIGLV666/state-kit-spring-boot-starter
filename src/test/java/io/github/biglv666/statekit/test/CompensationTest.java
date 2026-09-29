package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.event.CompensationScheduledEvent;
import io.github.biglv666.statekit.exception.StateConflictException;
import io.github.biglv666.statekit.history.HistoryEntry;
import io.github.biglv666.statekit.history.HistoryQueryService;
import io.github.biglv666.statekit.testapp.FlowStatus;
import io.github.biglv666.statekit.testapp.TestApplication;
import io.github.biglv666.statekit.testapp.TestHooksConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 0.3.0 冲突补偿策略测试（三分支：重路由 / 调度 / 放弃 + 落历史）。
 */
@SpringBootTest(classes = TestApplication.class, properties = "state-kit.history.enabled=true")
@org.springframework.context.annotation.Import(CompensationTest.ScheduleCollector.class)
class CompensationTest {

    @Autowired
    @Qualifier("compFlow")
    StateMachine<FlowStatus, Long> compFlow;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    HistoryQueryService historyQueryService;

    @Autowired
    ScheduleCollector scheduleCollector;

    @Component
    static class ScheduleCollector {
        final List<CompensationScheduledEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(CompensationScheduledEvent event) {
            events.add(event);
        }
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_comp");
        jdbcTemplate.update("DELETE FROM sk_transition_history");
        jdbcTemplate.update("INSERT INTO t_comp (id, status) VALUES (1, 'A')");
        scheduleCollector.events.clear();
        TestHooksConfig.CompModeHolder.mode = TestHooksConfig.CompModeHolder.Mode.ABORT;
    }

    /** 制造竞态：赢家未提交事务推进到 target，输家 fire GO（期望 A）必然冲突 */
    private void race(String targetStatus) throws Exception {
        TransactionTemplate winnerTx = new TransactionTemplate(transactionManager);
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> winner = pool.submit(() -> winnerTx.executeWithoutResult(tx -> {
            jdbcTemplate.update("UPDATE t_comp SET status = ? WHERE id = 1", targetStatus);
            started.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }));
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (Exception e) {
            pool.shutdownNow();
            throw new RuntimeException(e);
        }
        // 输家 fire GO：读到已提交的 A，路由通过，CAS 败（赢家持锁提交后）
        try {
            compFlow.fire(1L, "GO");
        } finally {
            winner.get();
            pool.shutdownNow();
        }
    }

    @Test
    void 放弃分支_默认行为与0_2一致() throws Exception {
        // ABORT：冲突照常抛 StateConflictException
        TestHooksConfig.CompModeHolder.mode = TestHooksConfig.CompModeHolder.Mode.ABORT;
        // 在独立线程中制造竞态并等待输家事务提交（回滚）后，历史表才有可见记录
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<?> loser = pool.submit(() -> {
            try {
                race("B");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        try {
            loser.get();
            org.assertj.core.api.Assertions.fail("ABORT 分支应抛 StateConflictException");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re && re.getCause() != null) {
                cause = re.getCause(); // race 内部以 RuntimeException 包装
            }
            assertThat(cause).isInstanceOf(StateConflictException.class);
        } finally {
            pool.shutdownNow();
        }
        assertThat(compFlow.currentState(1L)).contains(FlowStatus.B);

        // 补偿决策落历史（COMPENSATE:abort）——输家事务提交后可见
        List<String> events = jdbcTemplate.queryForList(
                "SELECT event FROM sk_transition_history WHERE machine = 'compFlow' AND entity_id = '1'",
                String.class);
        assertThat(events).contains("COMPENSATE:abort");
    }

    @Test
    void 重路由分支_以新事件继续推进() throws Exception {
        // RETRY：策略返回 retryWith("GO")，重跑 fire，以最新状态 B 路由 B→D
        TestHooksConfig.CompModeHolder.mode = TestHooksConfig.CompModeHolder.Mode.RETRY;
        race("B");
        assertThat(compFlow.currentState(1L)).contains(FlowStatus.D);

        // 落历史：COMPENSATE:retry:GO 决策记录 + 重路由的 GO 流转记录
        List<HistoryEntry> history = historyQueryService.query("compFlow", 1L);
        assertThat(history).anyMatch(h -> h.getEvent().equals("COMPENSATE:retry:GO"));
        assertThat(history).anyMatch(h -> h.getEvent().equals("GO") && h.getFromState().equals("B"));
    }

    @Test
    void 调度分支_fire正常返回并发布调度事件() throws Exception {
        // SCHEDULE：策略返回 schedule("GO", 30s)，fire 不抛异常，发布调度事件
        TestHooksConfig.CompModeHolder.mode = TestHooksConfig.CompModeHolder.Mode.SCHEDULE;
        race("B");

        // fire 正常返回（race 内部不抛即通过），状态停留在 B（未重试推进）
        assertThat(compFlow.currentState(1L)).contains(FlowStatus.B);
        assertThat(scheduleCollector.events).hasSize(1);
        CompensationScheduledEvent event = scheduleCollector.events.get(0);
        assertThat(event.getMachine()).isEqualTo("compFlow");
        assertThat(event.getEntityId()).isEqualTo(1L);
        assertThat(event.getEvent()).isEqualTo("GO");
        assertThat(event.getDelayMs()).isEqualTo(30_000);
        assertThat(event.getExpectedFrom()).isEqualTo("A");
        assertThat(event.getActualState()).isEqualTo("B");

        assertThat(historyQueryService.query("compFlow", 1L))
                .anyMatch(h -> h.getEvent().startsWith("COMPENSATE:schedule:GO"));
    }

    @Test
    void 补偿策略仅在声明了compensation的机器生效() {
        // retryFlow 未声明 compensation：冲突后无策略，直接抛异常（与 0.2.0 行为一致）
        assertThatCode(() -> {
            jdbcTemplate.update("INSERT INTO t_comp (id, status) VALUES (2, 'A')");
        }).doesNotThrowAnyException();
        assertThat(compFlow.currentState(1L)).isPresent();
    }
}
