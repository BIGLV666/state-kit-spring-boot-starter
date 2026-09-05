package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.testapp.FlowStatus;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.exception.StateConflictException;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 0.2.0 乐观锁双保险 + 冲突自动重试测试。
 */
@SpringBootTest(classes = TestApplication.class)
class RetryVersionTest {

    @Autowired
    @Qualifier("orderv")
    StateMachine<OrderStatus, Long> orderv;

    @Autowired
    @Qualifier("retryFlow")
    StateMachine<FlowStatus, Long> retryFlow;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_orderv");
        jdbcTemplate.update("DELETE FROM t_retry");
        jdbcTemplate.update("INSERT INTO t_orderv (id, status, version) VALUES (1, 'CREATED', 7)");
        jdbcTemplate.update("INSERT INTO t_retry (id, status) VALUES (11, 'A')");
    }

    @Test
    void 乐观锁CAS成功后version自增() {
        orderv.fire(1L, "PAY");

        assertThat(orderv.currentState(1L)).contains(OrderStatus.PAID);
        Integer version = jdbcTemplate.queryForObject("SELECT version FROM t_orderv WHERE id = 1", Integer.class);
        assertThat(version).isEqualTo(8);
    }

    @Test
    void version被外部修改后旧版本CAS失败() {
        // 模拟其它路径先推进了 version（乐观锁窗口）
        jdbcTemplate.update("UPDATE t_orderv SET version = 99 WHERE id = 1");
        // 框架读到的 version=99 是最新值，正常成功——先验证读版本正确
        orderv.fire(1L, "PAY");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM t_orderv WHERE id = 1", Integer.class))
                .isEqualTo(100);

        // 回滚状态再验证：并发竞态下旧 version 的 CAS 必败（未提交赢家同时改 version）
        jdbcTemplate.update("UPDATE t_orderv SET status = 'CREATED', version = 0 WHERE id = 1");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        TransactionTemplate winnerTx = new TransactionTemplate(transactionManager);
        CountDownLatch started = new CountDownLatch(1);
        Future<?> winner = pool.submit(() -> winnerTx.executeWithoutResult(tx -> {
            jdbcTemplate.update("UPDATE t_orderv SET status = 'PAID', version = version + 1 WHERE id = 1");
            started.countDown();
            try { Thread.sleep(600); } catch (InterruptedException ignored) { }
        }));
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            // 输家读到 CREATED/version=0，路由通过但 CAS 与赢家的行锁相争，赢家提交后 version 不匹配
            assertThatThrownBy(() -> orderv.fire(1L, "PAY"))
                    .isInstanceOf(StateConflictException.class);
            winner.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 冲突自动重试以最新状态重新路由() throws Exception {
        // 赢家把 A 推进到 B（未提交持锁）；输家 fire GO：首次按 A 路由但 CAS 失败，
        // 重试重读到 B，B 上 GO 有出边（B→D），第二次 CAS 成功 → 最终 D
        ExecutorService pool = Executors.newSingleThreadExecutor();
        TransactionTemplate winnerTx = new TransactionTemplate(transactionManager);
        CountDownLatch started = new CountDownLatch(1);
        Future<?> winner = pool.submit(() -> winnerTx.executeWithoutResult(tx -> {
            jdbcTemplate.update("UPDATE t_retry SET status = 'B' WHERE id = 11");
            started.countDown();
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }
        }));
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            retryFlow.fire(11L, "GO");
            winner.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdownNow();
        }
        assertThat(retryFlow.currentState(11L)).contains(FlowStatus.D);
    }
}
