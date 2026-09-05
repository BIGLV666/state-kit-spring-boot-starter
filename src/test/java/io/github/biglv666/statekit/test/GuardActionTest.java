package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.FireArg;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.testapp.TaskStatus;
import io.github.biglv666.statekit.exception.GuardRejectedException;
import io.github.biglv666.statekit.testapp.TestHooksConfig;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钩子测试：守卫拒绝、动作执行与上下文、动作异常整体回滚。
 */
@SpringBootTest(classes = TestApplication.class)
class GuardActionTest {

    @Autowired
    @Qualifier("order")
    StateMachine<OrderStatus, Long> order;

    @Autowired
    @Qualifier("task")
    StateMachine<TaskStatus, Long> task;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("DELETE FROM t_task");
        TestHooksConfig.LastTx.reset();
    }

    @Test
    void 守卫拒绝后状态与set列均未动() {
        jdbcTemplate.update("INSERT INTO t_order (id, status, pay_no) VALUES (1, 'PAID', 'T-0')");
        assertThatThrownBy(() -> order.fire(1L, "CANCEL", FireArg.param("reject", true)))
                .isInstanceOf(GuardRejectedException.class)
                .hasMessageContaining("order");

        assertThat(order.currentState(1L)).contains(OrderStatus.PAID);
        assertThat(jdbcTemplate.queryForObject("SELECT pay_no FROM t_order WHERE id = 1", String.class))
                .isEqualTo("T-0");
    }

    @Test
    void 守卫通过后动作执行且tx上下文正确() {
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'SHIPPED')");
        order.fire(1L, "SIGN", FireArg.param("signer", "张三"), FireArg.set("pay_no", "ignored"));

        var last = TestHooksConfig.LastTx.count;
        assertThat(last.get()).isEqualTo(1);
        assertThat(TestHooksConfig.LastTx.entityId).isEqualTo(1L);
        assertThat(TestHooksConfig.LastTx.event).isEqualTo("SIGN");
        assertThat(TestHooksConfig.LastTx.from).isEqualTo(OrderStatus.SHIPPED);
        assertThat(TestHooksConfig.LastTx.to).isEqualTo(OrderStatus.DONE);
        assertThat(jdbcTemplate.queryForObject("SELECT sign_time FROM t_order WHERE id = 1", java.sql.Timestamp.class))
                .isNotNull();
    }

    @Test
    void 守卫拒绝时动作不执行() {
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'SHIPPED')");
        assertThatThrownBy(() -> order.fire(1L, "SIGN", FireArg.param("reject", true)))
                .isInstanceOf(GuardRejectedException.class);
        assertThat(TestHooksConfig.LastTx.count.get()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT sign_time FROM t_order WHERE id = 1", java.sql.Timestamp.class))
                .isNull();
    }

    @Test
    void 动作抛异常整体回滚含动作自身的写与set列() {
        // task 机器 conflict-strategy=log 不影响正常流转；RUN: NEW -> RUNNING, action=boomAction
        jdbcTemplate.update("INSERT INTO t_task (id, status, note) VALUES (5, 'NEW', null)");
        jdbcTemplate.update("UPDATE t_task SET status = 'NEW' WHERE id = 5");

        assertThatThrownBy(() -> task.fire(5L, "RUN", FireArg.set("note", "should-rollback")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        // 状态回滚、boomAction 里的写回滚、set 列回滚
        assertThat(task.currentState(5L)).contains(TaskStatus.NEW);
        assertThat(jdbcTemplate.queryForObject("SELECT note FROM t_task WHERE id = 5", String.class))
                .isNull();
    }
}
