package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.ActionDescriptor;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.export.StateMachineExporter;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.3.0 可视化导出与可操作视图测试。
 */
@SpringBootTest(classes = TestApplication.class)
class ExportViewTest {

    @Autowired
    @Qualifier("order")
    StateMachine<OrderStatus, Long> order;

    @Autowired
    @Qualifier("orderExporter")
    StateMachineExporter orderExporter;

    @Autowired
    @Qualifier("wfItemExporter")
    StateMachineExporter wfItemExporter;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void Mermaid导出包含机器名_边_守卫动作_终态() {
        String mermaid = orderExporter.toMermaid();

        assertThat(mermaid).startsWith("stateDiagram-v2");
        assertThat(mermaid).contains("machine: order");
        assertThat(mermaid).contains("CREATED --> PAID : PAY");
        assertThat(mermaid).contains("PAID --> SHIPPED : SHIP [action: orderShipAction]");
        assertThat(mermaid).contains("[guard: cancelGuard]");
        assertThat(mermaid).contains("[guard: orderSignGuard]");
        assertThat(mermaid).contains("DONE --> [*]");   // 终态
        assertThat(mermaid).contains("CANCELLED --> [*]");
    }

    @Test
    void DOT导出包含节点_边与终态双圆() {
        String dot = orderExporter.toDot();
        assertThat(dot).contains("digraph order");
        assertThat(dot).contains("\"CREATED\" -> \"PAID\" [label=\"PAY\"]");
        assertThat(dot).contains("g:cancelGuard");
        assertThat(dot).contains("\"DONE\" [shape=doublecircle]");
    }

    @Test
    void 多源简写在导出中展开为独立边() {
        String mermaid = orderExporter.toMermaid();
        // [CREATED, PAID] --CANCEL--> CANCELLED 应展开为两条边
        assertThat(mermaid).contains("CREATED --> CANCELLED : CANCEL");
        assertThat(mermaid).contains("PAID --> CANCELLED : CANCEL");
    }

    @Test
    void 子机器的终态也可正确标识() {
        String mermaid = wfItemExporter.toMermaid();
        assertThat(mermaid).contains("PENDING --> DONE : OK");
        assertThat(mermaid).contains("DONE --> [*]");
    }

    @Test
    void availableActions返回结构化操作视图() {
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");

        List<ActionDescriptor> actions = order.availableActions(1L);
        assertThat(actions).hasSize(2);

        ActionDescriptor pay = actions.stream().filter(a -> a.event().equals("PAY")).findFirst().orElseThrow();
        assertThat(pay.to()).isEqualTo("PAID");
        assertThat(pay.isGuarded()).isFalse();

        ActionDescriptor cancel = actions.stream().filter(a -> a.event().equals("CANCEL")).findFirst().orElseThrow();
        assertThat(cancel.to()).isEqualTo("CANCELLED");
        assertThat(cancel.isGuarded()).isTrue();   // CANCEL 挂 cancelGuard

        // 终态无操作
        order.fire(1L, "CANCEL");
        assertThat(order.availableActions(1L)).isEmpty();
    }

    @Test
    void DSL的describe注入描述与param() {
        // yml 通道 order 机器未声明 description/params；reactive DSL 机器有，
        // 此处仅验证 yml 未声明时描述为空且不影响结构
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (2, 'CREATED')");
        List<ActionDescriptor> actions = order.availableActions(2L);
        assertThat(actions).allSatisfy(a -> {
            assertThat(a.requiredParams()).isNotNull();
            assertThat(a.description()).isNull();   // 未声明
        });
    }
}
