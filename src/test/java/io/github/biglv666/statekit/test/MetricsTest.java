package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.FireArg;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.exception.IllegalTransitionException;
import io.github.biglv666.statekit.exception.StateConflictException;
import io.github.biglv666.statekit.metrics.FireMetrics;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.testapp.TaskStatus;
import io.github.biglv666.statekit.testapp.TestApplication;
import io.github.biglv666.statekit.testapp.TestHooksConfig.CompModeHolder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 0.4.0 fire 指标测试：每次 fire 恰好一条 statekit.fire 记录（7 种 outcome 归因），
 * 重试与补偿决策计数独立成 meter。核心断言口径：tag（machine/event/from/to/outcome）
 * 与业务侧实际结果严格一致。
 *
 * <p>CAS 冲突类用例复用 ConflictTest 的竞态窗口构造：赢家线程在未提交事务里改状态
 * （持行锁），输家 fire 读到旧已提交态、路由通过、CAS 阻塞后 WHERE 重评不命中。</p>
 */
@SpringBootTest(classes = TestApplication.class)
class MetricsTest {

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
    @Qualifier("task")
    StateMachine<TaskStatus, Long> task;

    @Autowired
    @Qualifier("retryFlow")
    StateMachine<io.github.biglv666.statekit.testapp.FlowStatus, Long> retryFlow;

    @Autowired
    @Qualifier("compFlow")
    StateMachine<io.github.biglv666.statekit.testapp.FlowStatus, Long> compFlow;

    @Autowired
    MeterRegistry registry;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    private ExecutorService pool;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("DELETE FROM t_task");
        jdbcTemplate.update("DELETE FROM t_retry");
        jdbcTemplate.update("DELETE FROM t_comp");
        registry.clear();
        pool = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    /**
     * 在另一线程的未提交事务里把实体状态改为 winnerStatus（持行锁），
     * 让主线程的 fire 读到旧已提交态并阻塞在 CAS 行锁上，赢家提交后精确复现竞态窗口。
     */
    private Future<?> runWinner(String table, long id, String winnerStatus, CountDownLatch started) {
        TransactionTemplate winnerTx = new TransactionTemplate(transactionManager);
        return pool.submit(() -> winnerTx.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE " + table + " SET status = ? WHERE id = ?", winnerStatus, id);
            started.countDown();
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
            }
        }));
    }

    private Timer timer(String machine, String event, String from, String to, String outcome) {
        return registry.find("statekit.fire")
                .tags("machine", machine, "event", event, "from", from, "to", to, "outcome", outcome)
                .timer();
    }

    private double compensationCount(String decision, String actual) {
        var counter = registry.find("statekit.compensation")
                .tags("machine", "compFlow", "event", "GO", "actual", actual, "decision", decision)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void fire成功记录success指标含from_to与耗时() {
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");

        order.fire(1L, "PAY", FireArg.set("pay_no", "T-1"));

        Timer timer = timer("order", "PAY", "CREATED", "PAID", FireMetrics.OUTCOME_SUCCESS);
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isPositive();
        // 未产生失败类指标
        assertThat(registry.find("statekit.fire").tags("outcome", FireMetrics.OUTCOME_ILLEGAL).timer()).isNull();
        assertThat(registry.find("statekit.fire.retries").counter()).isNull();
    }

    @Test
    void 非法流转记录illegal指标_to缺失记为dash() {
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");

        assertThatThrownBy(() -> order.fire(1L, "SHIP"))
                .isInstanceOf(IllegalTransitionException.class);

        assertThat(timer("order", "SHIP", "CREATED", "-", FireMetrics.OUTCOME_ILLEGAL).count()).isEqualTo(1);
    }

    @Test
    void 实体不存在同样归因illegal() {
        assertThatThrownBy(() -> order.fire(99L, "PAY"))
                .isInstanceOf(IllegalTransitionException.class);

        assertThat(timer("order", "PAY", "-", "-", FireMetrics.OUTCOME_ILLEGAL).count()).isEqualTo(1);
    }

    @Test
    void 守卫拒绝记录guard_rejected指标() {
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");

        assertThatThrownBy(() -> order.fire(1L, "CANCEL", FireArg.param("reject", Boolean.TRUE)))
                .hasMessageContaining("守卫拒绝");

        assertThat(timer("order", "CANCEL", "CREATED", "-", FireMetrics.OUTCOME_GUARD_REJECTED).count())
                .isEqualTo(1);
    }

    @Test
    void CAS未命中抛异常记录conflict指标() throws Exception {
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");
        CountDownLatch started = new CountDownLatch(1);
        Future<?> winner = runWinner("t_order", 1L, "PAID", started);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // 输家读 CREATED、路由 CANCEL 通过，CAS 阻塞后重评不命中
        assertThatThrownBy(() -> order.fire(1L, "CANCEL"))
                .isInstanceOf(StateConflictException.class);
        winner.get();

        assertThat(timer("order", "CANCEL", "CREATED", "-", FireMetrics.OUTCOME_CONFLICT).count()).isEqualTo(1);
    }

    @Test
    void log策略静默冲突记录conflict_log指标() throws Exception {
        jdbcTemplate.update("INSERT INTO t_task (id, status) VALUES (1, 'NEW')");
        CountDownLatch started = new CountDownLatch(1);
        Future<?> winner = runWinner("t_task", 1L, "RUNNING", started);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        task.fire(1L, "RUN");   // conflict-strategy=log：CAS 未命中不抛异常

        winner.get();
        assertThat(task.currentState(1L)).contains(TaskStatus.RUNNING);
        assertThat(timer("task", "RUN", "NEW", "-", FireMetrics.OUTCOME_CONFLICT_LOG).count()).isEqualTo(1);
    }

    @Test
    void 动作抛异常记录error指标_动作异常不带from_tag() {
        jdbcTemplate.update("INSERT INTO t_task (id, status) VALUES (1, 'NEW')");

        assertThatThrownBy(() -> task.fire(1L, "RUN"))
                .isInstanceOf(IllegalStateException.class);

        // 状态回滚未变，fire 以未知异常结束：归因 error，from/to 均为 "-"
        assertThat(task.currentState(1L)).contains(TaskStatus.NEW);
        assertThat(timer("task", "RUN", "-", "-", FireMetrics.OUTCOME_ERROR).count()).isEqualTo(1);
    }

    @Test
    void 自动重试记录retries计数_重试后成功另有success记录() throws Exception {
        jdbcTemplate.update("INSERT INTO t_retry (id, status) VALUES (1, 'A')");
        CountDownLatch started = new CountDownLatch(1);
        // 赢家把状态推进到 B（B 有 GO 出边 → D）：输家首次 CAS 未命中后按最新状态重试成功
        Future<?> winner = runWinner("t_retry", 1L, "B", started);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        retryFlow.fire(1L, "GO");

        winner.get();
        assertThat(registry.find("statekit.fire.retries")
                .tags("machine", "retryFlow", "event", "GO").counter().count()).isEqualTo(1);
        assertThat(timer("retryFlow", "GO", "B", "D", FireMetrics.OUTCOME_SUCCESS).count()).isEqualTo(1);
        assertThat(retryFlow.currentState(1L))
                .contains(io.github.biglv666.statekit.testapp.FlowStatus.D);
    }

    @Test
    void 补偿retryWith产生两层记录_外层compensation_retry内层success() throws Exception {
        jdbcTemplate.update("INSERT INTO t_comp (id, status) VALUES (1, 'A')");
        CountDownLatch started = new CountDownLatch(1);
        // 赢家推进到 B：输家冲突后策略 retryWith(GO)，内层按 B→D 推进
        Future<?> winner = runWinner("t_comp", 1L, "B", started);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        CompModeHolder.mode = CompModeHolder.Mode.RETRY;
        try {
            compFlow.fire(1L, "GO");
        } finally {
            CompModeHolder.mode = CompModeHolder.Mode.ABORT;
            winner.get();
        }

        assertThat(compFlow.currentState(1L))
                .contains(io.github.biglv666.statekit.testapp.FlowStatus.D);
        assertThat(timer("compFlow", "GO", "A", "-", FireMetrics.OUTCOME_COMPENSATION_RETRY).count())
                .isEqualTo(1);
        assertThat(timer("compFlow", "GO", "B", "D", FireMetrics.OUTCOME_SUCCESS).count()).isEqualTo(1);
        assertThat(compensationCount(FireMetrics.DECISION_RETRY_WITH, "B")).isEqualTo(1);
    }

    @Test
    void 补偿schedule记录compensation_schedule与决策计数() throws Exception {
        jdbcTemplate.update("INSERT INTO t_comp (id, status) VALUES (1, 'A')");
        CountDownLatch started = new CountDownLatch(1);
        Future<?> winner = runWinner("t_comp", 1L, "C", started);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        CompModeHolder.mode = CompModeHolder.Mode.SCHEDULE;
        try {
            compFlow.fire(1L, "GO");   // 冲突后 schedule：fire 正常返回
        } finally {
            CompModeHolder.mode = CompModeHolder.Mode.ABORT;
            winner.get();
        }

        assertThat(compFlow.currentState(1L))
                .contains(io.github.biglv666.statekit.testapp.FlowStatus.C);
        assertThat(timer("compFlow", "GO", "A", "-", FireMetrics.OUTCOME_COMPENSATION_SCHEDULE).count())
                .isEqualTo(1);
        assertThat(compensationCount(FireMetrics.DECISION_SCHEDULE, "C")).isEqualTo(1);
    }

    @Test
    void 补偿abort按conflict归因_决策计数独立() throws Exception {
        jdbcTemplate.update("INSERT INTO t_comp (id, status) VALUES (1, 'A')");
        CountDownLatch started = new CountDownLatch(1);
        Future<?> winner = runWinner("t_comp", 1L, "C", started);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        CompModeHolder.mode = CompModeHolder.Mode.ABORT;
        try {
            assertThatThrownBy(() -> compFlow.fire(1L, "GO"))
                    .isInstanceOf(StateConflictException.class);
        } finally {
            winner.get();
        }

        assertThat(timer("compFlow", "GO", "A", "-", FireMetrics.OUTCOME_CONFLICT).count()).isEqualTo(1);
        assertThat(compensationCount(FireMetrics.DECISION_ABORT, "C")).isEqualTo(1);
    }
}
