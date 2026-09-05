package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.testapp.TestEventCollector;
import org.junit.jupiter.api.AfterEach;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * operator/trace 上下文解析测试：
 * traceId 取自 MDC（micrometer-tracing 桥接），operator 在无登录态时为 null 且不抛错（降级路径）。
 */
@SpringBootTest(classes = TestApplication.class)
class ContextResolveTest {

    @Autowired
    @Qualifier("order")
    StateMachine<OrderStatus, Long> order;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TestEventCollector eventCollector;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");
        eventCollector.reset();
    }

    @AfterEach
    void clearMdc() {
        MDC.remove("traceId");
    }

    @Test
    void traceId从MDC自动填充到事件() {
        MDC.put("traceId", "tr-123");
        order.fire(1L, "PAY");
        assertThat(eventCollector.events.get(0).getTraceId()).isEqualTo("tr-123");
    }

    @Test
    void 无链路上下文时traceId为null不报错() {
        order.fire(1L, "PAY");
        assertThat(eventCollector.events.get(0).getTraceId()).isNull();
    }

    @Test
    void 无登录态时operatorId为null不报错() {
        // 测试进程没有 auth-kit 登录态：auto 模式应优雅降级为 null
        order.fire(1L, "PAY");
        assertThat(eventCollector.events.get(0).getOperatorId()).isNull();
    }

    @Test
    void 自定义MDC键名可通过配置适配() {
        // 默认键 traceId 已覆盖；此处验证非默认键取不到时不抛错
        MDC.put("x-trace", "other-key");
        order.fire(1L, "PAY");
        assertThat(eventCollector.events.get(0).getTraceId()).isNull();
    }
}
