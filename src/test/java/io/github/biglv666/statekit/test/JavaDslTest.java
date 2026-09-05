package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.testapp.TestApplication;
import io.github.biglv666.statekit.define.DefinitionBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Java DSL 通道测试：与 yml 等价可用、DSL 构建期校验。
 */
@SpringBootTest(classes = TestApplication.class)
class JavaDslTest {

    /** 字段名 = machine 名（dslOrder），验证同泛型时按名消歧 */
    @Autowired
    StateMachine<OrderStatus, Long> dslOrder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void JavaDSL声明的状态机可完整流转() {
        jdbcTemplate.update("DELETE FROM t_order2");
        jdbcTemplate.update("INSERT INTO t_order2 (id, status) VALUES (9, 'CREATED')");

        dslOrder.fire(9L, "PAY");
        assertThat(dslOrder.currentState(9L)).contains(OrderStatus.PAID);

        dslOrder.fire(9L, "SHIP");
        assertThat(dslOrder.currentState(9L)).contains(OrderStatus.SHIPPED);
        assertThat(dslOrder.nextStates(9L)).isEmpty();
        assertThat(dslOrder.isFinal(9L)).isTrue();
    }

    @Test
    void 未声明业务表时build立即报错() {
        assertThatThrownBy(() -> StateMachine.define("bad", OrderStatus.class).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad")
                .hasMessageContaining("table");
    }

    @Test
    void action必须在transition之后声明() {
        DefinitionBuilder<OrderStatus> builder = StateMachine.define("bad3", OrderStatus.class)
                .table("t", "status", "id");
        assertThatThrownBy(() -> builder.action("someAction"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transition");
    }
}
