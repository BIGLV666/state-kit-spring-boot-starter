package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.FireArg;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.exception.StateConflictException;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.testapp.TaskStatus;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.AfterEach;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 冲突测试：CAS 未命中的异常语义（期望态/实际态）、冲突后按实际状态可正常流转。
 *
 * <p>竞态模拟方式：赢家线程在未提交事务里把状态改成 PAID（持行锁），
 * 输家线程此刻 fire —— 读状态只见已提交的 CREATED（READ_COMMITTED）、路由通过，
 * CAS 阻塞在行锁上，赢家提交后 WHERE 条件重评不命中，精确复现并发竞态窗口。</p>
 */
@SpringBootTest(classes = TestApplication.class)
class ConflictTest {

    @Autowired
    @Qualifier("order")
    StateMachine<OrderStatus, Long> order;

    @Autowired
    @Qualifier("task")
    StateMachine<TaskStatus, Long> task;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    private ExecutorService pool;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("DELETE FROM t_task");
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");
        pool = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    /**
     * 在另一线程的未提交事务里把实体状态改为 winnerState（持行锁），
     * 等输家的 fire 进场后（winnerStarted 信号 + 短暂让位）再提交。
     */
    private Future<?> runWinner(String winnerStatus, String payNo, CountDownLatch winnerStarted) {
        TransactionTemplate winnerTx = new TransactionTemplate(transactionManager);
        return pool.submit(() -> winnerTx.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE t_order SET status = ?, pay_no = ? WHERE id = 1",
                    winnerStatus, payNo);
            winnerStarted.countDown();
            // 让输家的 fire 读到旧已提交状态并阻塞在 CAS 行锁上
            try { Thread.sleep(800); } catch (InterruptedException ignored) { }
        }));
    }

    @Test
    void CAS未命中抛StateConflictException含期望态与实际态() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Future<?> winner = runWinner("PAID", "winner", started);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> order.fire(1L, "CANCEL"))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("CREATED")
                .hasMessageContaining("PAID")
                .hasMessageContaining("order");
        winner.get();

        assertThat(order.currentState(1L)).contains(OrderStatus.PAID);
    }

    @Test
    void 冲突后按实际状态流转正常() {
        jdbcTemplate.update("UPDATE t_order SET status = 'PAID' WHERE id = 1");
        order.fire(1L, "SHIP");
        assertThat(order.currentState(1L)).contains(OrderStatus.SHIPPED);
    }

    @Test
    void CAS失败后旧状态不可再fire() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Future<?> winner = runWinner("PAID", "winner", started);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // 输家以 CREATED 期望 fire CANCEL → 冲突；赢家提交后状态为 PAID，不可能被输家改写
        assertThatThrownBy(() -> order.fire(1L, "CANCEL"))
                .isInstanceOf(StateConflictException.class);
        winner.get();
        assertThat(order.currentState(1L)).contains(OrderStatus.PAID);
    }

    @Test
    void set列在冲突时不写入() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Future<?> winner = runWinner("PAID", "winner", started);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> order.fire(1L, "CANCEL", FireArg.set("pay_no", "loser")))
                .isInstanceOf(StateConflictException.class);
        winner.get();
        assertThat(jdbcTemplate.queryForObject("SELECT pay_no FROM t_order WHERE id = 1", String.class))
                .isEqualTo("winner");
    }

    @Test
    void task机器log策略下冲突静默() throws Exception {
        // task 机器 ABORT 是多源事件 [NEW, RUNNING]：赢家推进到 RUNNING，
        // 输家以读到的 NEW fire ABORT → 路由可过但 CAS 未命中 → log 策略静默
        jdbcTemplate.update("INSERT INTO t_task (id, status) VALUES (7, 'NEW')");
        TransactionTemplate winnerTx = new TransactionTemplate(transactionManager);
        CountDownLatch started = new CountDownLatch(1);
        Future<?> winner = pool.submit(() -> winnerTx.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE t_task SET status = 'RUNNING' WHERE id = 7");
            started.countDown();
            try { Thread.sleep(800); } catch (InterruptedException ignored) { }
        }));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatCode(() -> task.fire(7L, "ABORT")).doesNotThrowAnyException();
        winner.get();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM t_task WHERE id = 7", String.class))
                .isEqualTo("RUNNING");
    }
}
