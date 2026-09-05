package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.exception.IllegalTransitionException;
import io.github.biglv666.statekit.testapp.TestOrderService;
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
 * 事务边界测试：fire 加入外部事务（提交/回滚）与自开事务两种边界。
 */
@SpringBootTest(classes = TestApplication.class)
class TransactionBoundaryTest {

    @Autowired
    @Qualifier("order")
    StateMachine<OrderStatus, Long> order;

    @Autowired
    TestOrderService orderService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");
    }

    @Test
    void fire加入外部事务并正常提交() {
        orderService.payInTx(1L, "TX-OK");
        assertThat(order.currentState(1L)).contains(OrderStatus.PAID);
        assertThat(jdbcTemplate.queryForObject("SELECT pay_no FROM t_order WHERE id = 1", String.class))
                .isEqualTo("TX-OK");
    }

    @Test
    void 外部事务后续步骤失败时fire整体回滚() {
        assertThatThrownBy(() -> orderService.payThenFail(1L))
                .isInstanceOf(IllegalStateException.class);

        // 状态与 set 列都回滚
        assertThat(order.currentState(1L)).contains(OrderStatus.CREATED);
        assertThat(jdbcTemplate.queryForObject("SELECT pay_no FROM t_order WHERE id = 1", String.class))
                .isNull();
    }

    @Test
    void 无外部事务时fire自开事务() {
        orderService.payWithoutTx(1L, "SELF-TX");
        assertThat(order.currentState(1L)).contains(OrderStatus.PAID);
        assertThat(jdbcTemplate.queryForObject("SELECT pay_no FROM t_order WHERE id = 1", String.class))
                .isEqualTo("SELF-TX");
    }

    @Test
    void 外部事务内非法流转异常穿透且回滚() {
        assertThatThrownBy(() -> orderService.illegalFireInTx(1L))
                .isInstanceOf(IllegalTransitionException.class);
        assertThat(order.currentState(1L)).contains(OrderStatus.CREATED);
    }
}
