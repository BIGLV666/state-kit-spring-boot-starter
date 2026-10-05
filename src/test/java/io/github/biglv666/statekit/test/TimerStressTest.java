package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.testapp.TestApplication;
import io.github.biglv666.statekit.timer.TimerScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.5.0 停留超时压测（按 testskill：问题清单 → 用例 → 压测）：
 *
 * <ol>
 *     <li><b>大批量吞吐</b>：5000 实体到期，单次 scanOnce 排水（batch=200 → 25 批），
 *         断言全部推进 + 吞吐数据；</li>
 *     <li><b>并发幂等</b>：8 线程并发 scanOnce 同一批 400 实体，CAS 保证每实体
 *         恰好推进一次、总 success == 400、无重复；</li>
 *     <li><b>多轮无遗漏无重复</b>：连续多轮扫描，剩余到期数为 0 后后续轮次零触发。</li>
 * </ol>
 */
@SpringBootTest(classes = TestApplication.class)
class TimerStressTest {

    @Autowired
    @Qualifier("timerOrder")
    StateMachine<OrderStatus, Long> timerOrder;

    @Autowired
    TimerScanner timerScanner;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_timer_order");
    }

    private void seedExpired(long startId, int count) {
        Timestamp expired = Timestamp.from(Instant.now().minusSeconds(60 * 60));
        jdbcTemplate.batchUpdate(
                "INSERT INTO t_timer_order (id, status, create_time) VALUES (?, 'CREATED', ?)",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        ps.setLong(1, startId + i);
                        ps.setTimestamp(2, expired);
                    }

                    @Override
                    public int getBatchSize() {
                        return count;
                    }
                });
    }

    @Test
    void 五千实体单次排水吞吐() {
        seedExpired(1, 5000);

        long begin = System.nanoTime();
        int fired = timerScanner.scanOnce();
        long elapsedNanos = System.nanoTime() - begin;

        assertThat(fired).isEqualTo(5000);
        Long remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_timer_order WHERE status = 'CREATED'", Long.class);
        assertThat(remaining).isZero();
        System.out.printf("[压测] 5000 实体单次 scanOnce 排水：%d ms（%.0f 实体/秒，batch=200）%n",
                TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                5000.0 / (elapsedNanos / 1_000_000_000.0));
    }

    @Test
    void 八线程并发扫描幂等_每实体恰好推进一次() throws Exception {
        int total = 400;
        seedExpired(1, total);
        LongAdder success = new LongAdder();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CyclicBarrier barrier = new CyclicBarrier(8);
        CountDownLatch done = new CountDownLatch(8);
        for (int t = 0; t < 8; t++) {
            pool.submit(() -> {
                try {
                    barrier.await(30, TimeUnit.SECONDS);
                    success.add(timerScanner.scanOnce());   // 8 实例并发扫描同一批
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // CAS 保证每个实体恰好被推进一次（其余并发触发全部冲突）
        assertThat(success.sum()).isEqualTo(total);
        Long cancelled = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_timer_order WHERE status = 'CANCELLED'", Long.class);
        Long remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_timer_order WHERE status = 'CREATED'", Long.class);
        assertThat(cancelled).isEqualTo((long) total);
        assertThat(remaining).isZero();
        System.out.printf("[压测] 8 线程并发 scanOnce × %d 实体：总 success=%d（严格等于实体数，零重复零遗漏）%n",
                total, success.sum());
    }

    @Test
    void 多轮扫描无遗漏无重复() {
        seedExpired(1, 300);

        int first = timerScanner.scanOnce();
        int second = timerScanner.scanOnce();
        int third = timerScanner.scanOnce();

        // 排水语义：首轮清空全部到期，后续轮次零触发
        assertThat(first).isEqualTo(300);
        assertThat(second).isZero();
        assertThat(third).isZero();
        List<Long> count = jdbcTemplate.queryForList(
                "SELECT COUNT(*) FROM t_timer_order WHERE status = 'CANCELLED'", Long.class);
        assertThat(count.get(0)).isEqualTo(300L);
    }
}
