package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.export.StateKitDiagramController;
import io.github.biglv666.statekit.metrics.StateKitEndpoint;
import io.github.biglv666.statekit.metrics.StateKitHealthIndicator;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 0.4.0 观测端点测试（Bean 级）：验证健康指示器、Actuator 端点的条件装配与数据组装。
 * 说明：单模块 Maven 无法仅为端点测试隔离 web classpath（引入 servlet 容器会把全部
 * 测试上下文切到 web 环境并破坏 web-common 装配），故 HTTP 映射层交由 Boot 标准机制，
 * 此处覆盖 Bean 装配、输出结构与 0.3.0 REST 控制器回归。
 */
@SpringBootTest(classes = TestApplication.class,
        properties = "management.endpoints.exposure.include=statekit")
class ObservabilityTest {

    @Autowired
    StateKitEndpoint stateKitEndpoint;

    @Autowired
    StateKitHealthIndicator stateKitHealthIndicator;

    @Autowired
    StateKitDiagramController diagramController;

    @Test
    void actuator端点返回全部机器摘要() {
        Map<String, Object> machines = stateKitEndpoint.machines();

        assertThat(machines).containsKeys("order", "task", "flow", "retryFlow", "compFlow");
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) machines.get("order");
        assertThat(order.get("table")).isEqualTo("t_order");
        assertThat(order.get("stateType")).isEqualTo("io.github.biglv666.statekit.testapp.OrderStatus");
        assertThat(order.get("conflictStrategy")).isEqualTo("THROW");
        assertThat(order).doesNotContainKey("machineCount");   // machineCount 只在健康检查详情里
        assertThat(order.get("transitions")).toString().contains("PAY");
    }

    @Test
    void 单机详情含mermaid图与边定义() {
        Map<String, Object> detail = stateKitEndpoint.machine("order");

        assertThat(detail.get("table")).isEqualTo("t_order");
        assertThat((String) detail.get("mermaid"))
                .startsWith("stateDiagram-v2")
                .contains("CREATED --> PAID : PAY")
                .contains("action: orderShipAction");
        assertThat(detail.get("finalStates")).toString().contains("DONE");
    }

    @Test
    void 查询不存在的机器报错() {
        assertThatThrownBy(() -> stateKitEndpoint.machine("noSuchMachine"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void 健康检查上报stateKit详情() {
        Health health = stateKitHealthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("history", "disabled")
                .containsEntry("bypass", "off")
                .containsEntry("metrics", "enabled");
        @SuppressWarnings("unchecked")
        java.util.List<String> machines = (java.util.List<String>) health.getDetails().get("machines");
        assertThat(machines).contains("order", "task", "flow");
        assertThat(health.getDetails().get("machineCount")).isEqualTo(machines.size());
    }

    @Test
    void 三版本REST导出端点回归() {
        String mermaid = diagramController.diagram("order", "mermaid");
        assertThat(mermaid).startsWith("stateDiagram-v2");

        String dot = diagramController.diagram("order", "dot");
        assertThat(dot).contains("digraph order");

        Map<String, String> all = diagramController.diagramAll("order");
        assertThat(all).containsKeys("mermaid", "dot");
    }
}
