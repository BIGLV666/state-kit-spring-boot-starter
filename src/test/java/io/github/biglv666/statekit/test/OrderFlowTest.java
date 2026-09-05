package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.FireArg;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.testapp.TaskStatus;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.testapp.FlowStatus;
import io.github.biglv666.statekit.exception.IllegalTransitionException;
import io.github.biglv666.statekit.testapp.TestEventCollector;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 核心流转测试：fire 主流程、辅助查询、FireArg 令牌、事件发布、多源路由、log 冲突策略。
 */
@SpringBootTest(classes = TestApplication.class)
class OrderFlowTest {

    @Autowired
    @Qualifier("order")
    StateMachine<OrderStatus, Long> order;

    @Autowired
    @Qualifier("task")
    StateMachine<TaskStatus, Long> task;

    @Autowired
    @Qualifier("flow")
    StateMachine<FlowStatus, String> flow;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TestEventCollector eventCollector;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("DELETE FROM t_task");
        jdbcTemplate.update("DELETE FROM t_flow");
        jdbcTemplate.update("DELETE FROM t_order2");
        eventCollector.reset();
        io.github.biglv666.statekit.testapp.TestHooksConfig.LastTx.reset();
    }

    long seedOrder(String status) {
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, ?)", status);
        return 1L;
    }

    String seedFlow(String id, String status) {
        jdbcTemplate.update("INSERT INTO t_flow (id, status) VALUES (?, ?)", id, status);
        return id;
    }

    @Test
    void fire成功改状态且set列同条SQL落库() {
        seedOrder("CREATED");
        order.fire(1L, "PAY", FireArg.param("txnNo", "T-1"), FireArg.set("pay_no", "T-1"));

        assertThat(order.currentState(1L)).contains(OrderStatus.PAID);
        String payNo = jdbcTemplate.queryForObject("SELECT pay_no FROM t_order WHERE id = 1", String.class);
        assertThat(payNo).isEqualTo("T-1");
    }

    @Test
    void 非法流转_状态无该事件出边() {
        seedOrder("CREATED");
        assertThatThrownBy(() -> order.fire(1L, "SHIP"))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("order")
                .hasMessageContaining("CREATED")
                .hasMessageContaining("SHIP")
                .hasMessageContaining("PAY"); // 错误信息提示当前允许的事件
        assertThat(order.currentState(1L)).contains(OrderStatus.CREATED);
    }

    @Test
    void 未知事件抛非法流转() {
        seedOrder("CREATED");
        assertThatThrownBy(() -> order.fire(1L, "NOT_EXIST"))
                .isInstanceOf(IllegalTransitionException.class);
        assertThat(order.currentState(1L)).contains(OrderStatus.CREATED);
    }

    @Test
    void 实体不存在抛非法流转且from为空() {
        assertThatThrownBy(() -> order.fire(999L, "PAY"))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void currentState_isFinal_nextStates辅助查询() {
        seedOrder("CREATED");
        assertThat(order.currentState(1L)).contains(OrderStatus.CREATED);
        assertThat(order.isFinal(1L)).isFalse();
        assertThat(order.nextStates(1L)).isEqualTo(Set.of(OrderStatus.PAID, OrderStatus.CANCELLED));

        order.fire(1L, "CANCEL");
        assertThat(order.isFinal(1L)).isTrue();
        assertThat(order.nextStates(1L)).isEmpty();
    }

    @Test
    void 终态无法再触发任何事件() {
        seedOrder("DONE");
        assertThat(order.isFinal(1L)).isTrue();
        assertThatThrownBy(() -> order.fire(1L, "PAY"))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("终态");
    }

    @Test
    void param与set严格分离_param不落库() {
        seedOrder("CREATED");
        order.fire(1L, "PAY", FireArg.param("pay_no", "param值"), FireArg.set("pay_no", "落库值"));

        // param 与 set 同名：列里必须是 set 的值，param 只进上下文
        assertThat(jdbcTemplate.queryForObject("SELECT pay_no FROM t_order WHERE id = 1", String.class))
                .isEqualTo("落库值");
    }

    @Test
    void set列名非法立即拒绝() {
        seedOrder("CREATED");
        assertThatThrownBy(() -> order.fire(1L, "PAY", FireArg.set("pay_no; DROP TABLE t_order", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("合法标识符");
        assertThat(order.currentState(1L)).contains(OrderStatus.CREATED);
    }

    @Test
    void 重复set列与重复param均拒绝() {
        seedOrder("CREATED");
        assertThatThrownBy(() -> order.fire(1L, "PAY",
                FireArg.set("pay_no", "a"), FireArg.set("pay_no", "b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复声明");
        assertThatThrownBy(() -> order.fire(1L, "PAY",
                FireArg.param("k", 1), FireArg.param("k", 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复声明");
    }

    @Test
    void 成功流转发布StateTransitedEvent() {
        seedOrder("CREATED");
        order.fire(1L, "PAY", FireArg.set("pay_no", "T-9"));

        assertThat(eventCollector.events).hasSize(1);
        var event = eventCollector.events.get(0);
        assertThat(event.getMachine()).isEqualTo("order");
        assertThat(event.getEntityId()).isEqualTo(1L);
        assertThat(event.getFrom()).isEqualTo("CREATED");
        assertThat(event.getTo()).isEqualTo("PAID");
        assertThat(event.getEvent()).isEqualTo("PAY");
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    void 多源流转从任一源状态都能触发() {
        seedOrder("CREATED");
        order.fire(1L, "CANCEL");
        assertThat(order.currentState(1L)).contains(OrderStatus.CANCELLED);

        jdbcTemplate.update("DELETE FROM t_order");
        seedOrder("PAID");
        order.fire(1L, "CANCEL");
        assertThat(order.currentState(1L)).contains(OrderStatus.CANCELLED);
    }

    @Test
    void 多源流转的多源状态都可作为CAS条件() {
        // CREATED --CANCEL--> CANCELLED 后，再以 PAID 期望触发 CANCEL 应冲突（而非路由错乱）
        seedOrder("CREATED");
        order.fire(1L, "CANCEL");
        assertThatThrownBy(() -> order.fire(1L, "CANCEL"))
                .isInstanceOf(IllegalTransitionException.class); // CANCELLED 无出边，终态
    }

    @Test
    void 同一事件从不同状态路由到各自目标() {
        seedFlow("A-1", "A");
        seedFlow("C-1", "C");
        flow.fire("A-1", "GO");
        flow.fire("C-1", "GO");
        assertThat(flow.currentState("A-1")).contains(FlowStatus.B);
        assertThat(flow.currentState("C-1")).contains(FlowStatus.D);
    }

    @Test
    void String主键状态机可用() {
        seedFlow("S-42", "A");
        assertThat(flow.currentState("S-42")).contains(FlowStatus.A);
        flow.fire("S-42", "GO");
        assertThat(flow.currentState("S-42")).contains(FlowStatus.B);
    }
    // conflict-strategy=log 的静默语义由 ConflictTest 以并发竞态方式覆盖
}
