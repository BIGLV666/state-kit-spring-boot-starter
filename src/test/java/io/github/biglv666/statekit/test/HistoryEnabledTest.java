package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.FireArg;
import io.github.biglv666.statekit.testapp.OrderStatus;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.testapp.TaskStatus;
import io.github.biglv666.statekit.history.HistoryEntry;
import io.github.biglv666.statekit.history.HistoryQueryService;
import io.github.biglv666.statekit.history.JdbcHistoryRecorder;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 历史模块测试（history.enabled=true）：自动建表、同事务写入、回滚一致、幂等建表、查询服务。
 */
@SpringBootTest(classes = TestApplication.class, properties = "state-kit.history.enabled=true")
class HistoryEnabledTest {

    @Autowired
    @Qualifier("order")
    StateMachine<OrderStatus, Long> order;

    @Autowired
    @Qualifier("task")
    StateMachine<TaskStatus, Long> task;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    HistoryQueryService historyQueryService;

    @Autowired
    JdbcHistoryRecorder historyRecorder;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM t_order");
        jdbcTemplate.update("DELETE FROM t_task");
        jdbcTemplate.update("DELETE FROM sk_transition_history");
    }

    @Test
    void 启动自动建表且结构完整() {
        Integer columns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'SK_TRANSITION_HISTORY'
                """, Integer.class);
        assertThat(columns).isGreaterThanOrEqualTo(9);
    }

    @Test
    void fire写入完整历史记录() {
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");
        order.fire(1L, "PAY", FireArg.set("pay_no", "T-1"));

        List<HistoryEntry> history = historyQueryService.query("order", 1L);
        assertThat(history).hasSize(1);
        HistoryEntry entry = history.get(0);
        assertThat(entry.getMachine()).isEqualTo("order");
        assertThat(entry.getEntityId()).isEqualTo("1");
        assertThat(entry.getFromState()).isEqualTo("CREATED");
        assertThat(entry.getToState()).isEqualTo("PAID");
        assertThat(entry.getEvent()).isEqualTo("PAY");
        assertThat(entry.getCreateTime()).isNotNull();
    }

    @Test
    void 历史按时间序形成流转轨迹() {
        jdbcTemplate.update("INSERT INTO t_order (id, status) VALUES (1, 'CREATED')");
        order.fire(1L, "PAY");
        order.fire(1L, "SHIP");
        order.fire(1L, "SIGN");

        List<HistoryEntry> history = historyQueryService.query("order", 1L);
        assertThat(history).extracting(HistoryEntry::getEvent)
                .containsExactly("PAY", "SHIP", "SIGN");
        assertThat(history).extracting(HistoryEntry::getFromState)
                .containsExactly("CREATED", "PAID", "SHIPPED");
        assertThat(historyQueryService.count("order", 1L)).isEqualTo(3);
    }

    @Test
    void 业务回滚时历史同事务回滚() {
        jdbcTemplate.update("INSERT INTO t_task (id, status) VALUES (5, 'NEW')");
        // boomAction 抛异常 → 历史与业务一起回滚
        assertThatThrownBy(() -> task.fire(5L, "RUN"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(historyQueryService.count("task", 5L)).isZero();
    }

    @Test
    void 建表幂等_重复初始化不报错() {
        assertThatCode(() -> {
            historyRecorder.initialize();
            historyRecorder.initialize();
        }).doesNotThrowAnyException();
    }

    @Test
    void 未发生流转的实体历史为空() {
        assertThat(historyQueryService.query("order", 42L)).isEmpty();
    }
}
