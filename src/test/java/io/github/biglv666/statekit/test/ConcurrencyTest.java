package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.FireArg;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发测试：两线程同时对同一实体 fire 同一事件，CAS 行锁保证恰好一成一败。
 */
@SpringBootTest(classes = TestApplication.class)
class ConcurrencyTest {

    @Autowired
    @Qualifier("order")
    StateMachine<OrderStatus, Long> order;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_order");
    }

    @Test
    void 并发双流转恰好一成一败() throws Exception {
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        // 每个线程独立 fire：内含自开事务的 SELECT + CAS
        java.util.List<Future<Boolean>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            results.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    order.fire(1L, "PAY", FireArg.set("pay_no", "T-" + n));
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int success = 0;
        for (Future<Boolean> f : results) {
            if (f.get(30, TimeUnit.SECONDS)) {
                success++;
            }
        }
        pool.shutdown();

        // CAS 的 WHERE status=CREATED 条件保证状态只被推进一次
        assertThat(success).isEqualTo(1);
        assertThat(order.currentState(1L)).contains(OrderStatus.PAID);
        String payNo = jdbcTemplate.queryForObject(
                "SELECT pay_no FROM t_order WHERE id = 1", String.class);
        assertThat(payNo).startsWith("T-"); // 唯一赢家的 set 列生效
    }
}
