package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.FireArg;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.exception.StateConflictException;
import io.github.biglv666.statekit.metrics.FireMetrics;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.testapp.TaskStatus;
import io.github.biglv666.statekit.testapp.TestApplication;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.4.0 指标压测（按 testskill：问题清单 → 用例 → 压测）：
 *
 * <ol>
 *     <li><b>混合负载一致性</b>：阶段 A——8 线程各 800 次私有实体 fire（success/illegal/
 *         guard_rejected/error 各 200 次，结果确定）；阶段 B——8 线程屏障竞态同实体
 *         × 200 轮（CAS 保证每轮恰好 1 次 success，其余为 conflict / illegal——
 *         赢家整体提交快于慢读线程时，慢读者按 PAID 路由得 illegal，属框架合法行为）。
 *         meter 侧各 outcome 计数必须与 worker 实测<b>严格相等</b>（并发下不丢不重），
 *         且每轮成功数恰为 1（成功总数确定）；</li>
 *     <li><b>开销基准</b>：MicrometerFireMetrics.record 与 NOOP.record 微基准对比
 *         （SimpleMeterRegistry），给出单次调用开销数据。</li>
 * </ol>
 */
@SpringBootTest(classes = TestApplication.class)
class MetricsStressTest {

    @TestConfiguration
    static class MeterConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private static final int THREADS = 8;
    private static final int PRIVATE_OPS = 200;      // 每线程私有实体各 200 次
    private static final int ROUNDS = 200;           // 屏障竞态轮数
    private static final long CONTENDED_ID = 999_999L;
    private static final long TIMEOUT_SEC = 60;

    @Autowired
    @Qualifier("order")
    StateMachine<OrderStatus, Long> order;

    @Autowired
    @Qualifier("task")
    StateMachine<TaskStatus, Long> task;

    @Autowired
    MeterRegistry registry;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("DELETE FROM t_task");
        registry.clear();
    }

    /** meter 侧按 outcome 汇总的 fire 计数 */
    private Map<String, Double> meterCountsByOutcome() {
        Map<String, Double> result = new ConcurrentHashMap<>();
        for (Timer timer : registry.find("statekit.fire").timers()) {
            String outcome = timer.getId().getTag("outcome");
            if (outcome != null) {
                result.put(outcome, result.getOrDefault(outcome, 0.0) + timer.count());
            }
        }
        return result;
    }

    @Test
    void 混合负载下指标计数与业务结果严格一致() throws Exception {
        LongAdder total = new LongAdder();
        LongAdder successB = new LongAdder();
        LongAdder conflictB = new LongAdder();
        LongAdder illegalB = new LongAdder();
        Map<String, LongAdder> expected = new ConcurrentHashMap<>();
        for (String outcome : List.of(FireMetrics.OUTCOME_SUCCESS, FireMetrics.OUTCOME_ILLEGAL,
                FireMetrics.OUTCOME_GUARD_REJECTED, FireMetrics.OUTCOME_ERROR, FireMetrics.OUTCOME_CONFLICT)) {
            expected.put(outcome, new LongAdder());
        }

        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (?, 'CREATED')", CONTENDED_ID);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        List<Future<?>> futures = new java.util.ArrayList<>();
        long begin = System.nanoTime();
        for (int t = 0; t < THREADS; t++) {
            final int threadNo = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    // 阶段 A：私有实体，结果确定（success/illegal/guard_rejected/error 各 PRIVATE_OPS 次）
                    for (int i = 0; i < PRIVATE_OPS; i++) {
                        long id = threadNo * 1_000_000L + i;
                        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (?, 'CREATED')", id);
                        order.fire(id, "PAY");
                        expected.get(FireMetrics.OUTCOME_SUCCESS).increment();
                        total.increment();

                        try {
                            order.fire(id, "NOPE");
                        } catch (Exception ignored) {
                        }
                        expected.get(FireMetrics.OUTCOME_ILLEGAL).increment();
                        total.increment();

                        try {
                            order.fire(id, "CANCEL", FireArg.param("reject", Boolean.TRUE));
                        } catch (Exception ignored) {
                        }
                        expected.get(FireMetrics.OUTCOME_GUARD_REJECTED).increment();
                        total.increment();

                        jdbcTemplate.update("INSERT INTO t_task (id, status) VALUES (?, 'NEW')", id);
                        try {
                            task.fire(id, "RUN");
                        } catch (Exception ignored) {
                        }
                        expected.get(FireMetrics.OUTCOME_ERROR).increment();
                        total.increment();
                    }
                    // 阶段 B：屏障竞态同实体——thread 0 单条 UPDATE 重置，CAS 保证每轮恰好 1 成
                    for (int round = 0; round < ROUNDS; round++) {
                        if (threadNo == 0) {
                            jdbcTemplate.update("UPDATE t_order SET status = 'CREATED' WHERE id = ?", CONTENDED_ID);
                        }
                        barrier.await(TIMEOUT_SEC, TimeUnit.SECONDS);
                        try {
                            order.fire(CONTENDED_ID, "PAY");
                            expected.get(FireMetrics.OUTCOME_SUCCESS).increment();
                            successB.increment();
                        } catch (StateConflictException e) {
                            expected.get(FireMetrics.OUTCOME_CONFLICT).increment();
                            conflictB.increment();
                        } catch (io.github.biglv666.statekit.exception.IllegalTransitionException e) {
                            // 慢读者在赢家提交后读态 → 按 PAID 路由：合法的确定性错误
                            expected.get(FireMetrics.OUTCOME_ILLEGAL).increment();
                            illegalB.increment();
                        }
                        total.increment();
                        barrier.await(TIMEOUT_SEC, TimeUnit.SECONDS);
                    }
                } catch (Throwable e) {
                    throw new RuntimeException("worker-" + threadNo, e);
                } finally {
                    done.countDown();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(300, TimeUnit.SECONDS);   // worker 异常必须让测试失败
        }
        long elapsedNanos = System.nanoTime() - begin;

        double tps = total.sum() / (elapsedNanos / 1_000_000_000.0);
        System.out.printf("[压测] 混合负载：8 线程（私有 4×%d + 竞态 %d 轮）= %d 次 fire，耗时 %.2fs，TPS ≈ %.0f%n",
                PRIVATE_OPS, ROUNDS, total.sum(), elapsedNanos / 1_000_000_000.0, tps);
        System.out.printf("[压测] 竞态阶段分布：success=%d conflict=%d illegal=%d（success 每轮恰 1）%n",
                successB.sum(), conflictB.sum(), illegalB.sum());

        // CAS 语义：每轮恰好 1 次 success（WHERE status=CREATED 只可能命中一次）
        assertThat(successB.sum()).isEqualTo(ROUNDS);
        assertThat(conflictB.sum() + illegalB.sum()).isEqualTo((long) (THREADS - 1) * ROUNDS);

        Map<String, Double> actual = meterCountsByOutcome();
        for (String outcome : expected.keySet()) {
            assertThat(actual.getOrDefault(outcome, 0.0))
                    .as("outcome=%s 应与业务侧统计严格一致", outcome)
                    .isEqualTo((double) expected.get(outcome).sum());
        }
        double sum = actual.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).as("每次 fire 恰好一条记录").isEqualTo(total.sum());
    }

    @Test
    void 同实体竞态meter与worker实测严格一致() throws Exception {
        LongAdder successTotal = new LongAdder();
        LongAdder conflictTotal = new LongAdder();
        LongAdder illegalTotal = new LongAdder();

        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (int round = 0; round < ROUNDS; round++) {
            jdbcTemplate.update("UPDATE t_order SET status = 'CREATED' WHERE id = 1");
            CyclicBarrier barrier = new CyclicBarrier(THREADS);
            CountDownLatch roundDone = new CountDownLatch(THREADS);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    try {
                        barrier.await(TIMEOUT_SEC, TimeUnit.SECONDS);
                        try {
                            order.fire(1L, "PAY");
                            successTotal.increment();
                        } catch (StateConflictException e) {
                            conflictTotal.increment();
                        } catch (io.github.biglv666.statekit.exception.IllegalTransitionException e) {
                            illegalTotal.increment();
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        roundDone.countDown();
                    }
                });
            }
            assertThat(roundDone.await(TIMEOUT_SEC, TimeUnit.SECONDS)).isTrue();
            if (failure.get() != null) {
                throw new AssertionError("第 " + round + " 轮出现预期外异常：" + failure.get(), failure.get());
            }
        }
        pool.shutdown();

        assertThat(successTotal.sum()).isEqualTo(ROUNDS);
        assertThat(conflictTotal.sum() + illegalTotal.sum()).isEqualTo((long) (THREADS - 1) * ROUNDS);

        Double successMeter = meterCountsByOutcome().get(FireMetrics.OUTCOME_SUCCESS);
        Double conflictMeter = meterCountsByOutcome().get(FireMetrics.OUTCOME_CONFLICT);
        Double illegalMeter = meterCountsByOutcome().get(FireMetrics.OUTCOME_ILLEGAL);
        assertThat(successMeter).isEqualTo((double) successTotal.sum());
        assertThat(conflictMeter).isEqualTo((double) conflictTotal.sum());
        assertThat(illegalMeter).isEqualTo((double) illegalTotal.sum());
        System.out.printf("[压测] 同实体竞态：8 线程 × %d 轮，success=%d conflict=%d illegal=%d，与 meter 严格一致%n",
                ROUNDS, successTotal.sum(), conflictTotal.sum(), illegalTotal.sum());
    }

    @Test
    void record调用开销微基准() {
        FireMetrics noop = FireMetrics.NOOP;
        FireMetrics micro = new io.github.biglv666.statekit.metrics.MicrometerFireMetrics(registry);
        int warmup = 50_000;
        int rounds = 1_000_000;

        // 预热 + 首次注册（避免 meter 注册计入测量）
        micro.record("bench", "PAY", "CREATED", "PAID", FireMetrics.OUTCOME_SUCCESS, 1);
        for (int i = 0; i < warmup; i++) {
            noop.record("bench", "PAY", "CREATED", "PAID", FireMetrics.OUTCOME_SUCCESS, 1);
            micro.record("bench", "PAY", "CREATED", "PAID", FireMetrics.OUTCOME_SUCCESS, 1);
        }

        long t0 = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            noop.record("bench", "PAY", "CREATED", "PAID", FireMetrics.OUTCOME_SUCCESS, 1);
        }
        long noopNanos = System.nanoTime() - t0;

        long t1 = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            micro.record("bench", "PAY", "CREATED", "PAID", FireMetrics.OUTCOME_SUCCESS, 1);
        }
        long microNanos = System.nanoTime() - t1;

        double noopNs = noopNanos / (double) rounds;
        double microNs = microNanos / (double) rounds;
        System.out.printf("[压测] record 单次开销：NOOP ≈ %.0f ns，Micrometer(Simple) ≈ %.0f ns，增量 ≈ %.0f ns/次%n",
                noopNs, microNs, microNs - noopNs);

        // 清理基准 meter 避免污染其他用例
        registry.clear();
    }
}
