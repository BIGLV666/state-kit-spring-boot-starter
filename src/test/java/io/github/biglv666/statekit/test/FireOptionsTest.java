package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.FireOptions;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.exception.IllegalTransitionException;
import io.github.biglv666.statekit.history.HistoryEntry;
import io.github.biglv666.statekit.history.HistoryQueryService;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 0.2.0 fire 重载测试：FireOptions.skipHistory 单次豁免历史、tryFire 冲突返回 false。
 */
@SpringBootTest(classes = TestApplication.class, properties = "state-kit.history.enabled=true")
class FireOptionsTest {

    @Autowired
    @Qualifier("order")
    StateMachine<OrderStatus, Long> order;

    @Autowired
    HistoryQueryService historyQueryService;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("DELETE FROM sk_transition_history");
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");
    }

    @Test
    void 默认fire正常记历史() {
        order.fire(1L, "PAY");
        assertThat(historyQueryService.count("order", 1L)).isEqualTo(1);
    }

    @Test
    void skipHistory单次豁免_状态照常变更() {
        order.fire(1L, "PAY", FireOptions.skipHistory());

        assertThat(order.currentState(1L)).contains(OrderStatus.PAID);          // 状态变了
        assertThat(historyQueryService.count("order", 1L)).isZero();            // 历史没记

        // 后续正常 fire 恢复记录
        order.fire(1L, "CANCEL");
        assertThat(historyQueryService.count("order", 1L)).isEqualTo(1);
        List<HistoryEntry> history = historyQueryService.query("order", 1L);
        assertThat(history.get(0).getFromState()).isEqualTo("PAID");            // 只记了第二段
    }

    @Test
    void tryFire成功返回true冲突返回false() throws Exception {
        // 成功路径
        assertThat(order.tryFire(1L, "PAY")).isTrue();

        // 冲突路径：赢家未提交事务持锁，输家 tryFire 读到旧态、路由通过、CAS 败 → false
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (2, 'CREATED')");
        org.springframework.transaction.support.TransactionTemplate winnerTx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<?> winner = pool.submit(() -> winnerTx.executeWithoutResult(tx -> {
            jdbcTemplate.update("UPDATE t_order SET status = 'PAID' WHERE id = 2");
            started.countDown();
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
        }));
        try {
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(order.tryFire(2L, "CANCEL")).isFalse();
            winner.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tryFire的确定性错误照常抛出() {
        // 终态 CREATED 无出边时触发 SIGN：非法流转必须抛而不是返回 false
        jdbcTemplate.update("UPDATE t_order SET status = 'DONE' WHERE id = 1");
        assertThatThrownBy(() -> order.tryFire(1L, "PAY"))
                .isInstanceOf(IllegalTransitionException.class);
    }
}
