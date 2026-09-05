package io.github.biglv666.statekit.test;

import io.github.biglv666.statekit.history.HistoryDwellService;
import io.github.biglv666.statekit.testapp.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.2.0 状态停留时长统计测试（基于历史表相邻记录推算停留区间）。
 */
@SpringBootTest(classes = TestApplication.class, properties = "state-kit.history.enabled=true")
class DwellStatsTest {

    @Autowired
    HistoryDwellService dwellService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM sk_transition_history");
    }

    private void seed(long id, String entityId, String from, String to, String event, LocalDateTime time) {
        jdbcTemplate.update("""
                INSERT INTO sk_transition_history (machine, entity_id, from_state, to_state, event, create_time)
                VALUES ('order', ?, ?, ?, ?, ?)
                """, entityId, from, to, event, time);
    }

    @Test
    void 相邻记录推算停留时长与未完成停留() {
        LocalDateTime now = LocalDateTime.now();
        seed(1, "1", "CREATED", "PAID", "PAY", now.minusMinutes(60));
        seed(2, "1", "PAID", "SHIPPED", "SHIP", now.minusMinutes(20));

        List<HistoryDwellService.StateDwell> stats = dwellService.stats("order");

        // 停留口径：第 i 条记录的 to_state 停留 = 第 i+1 条时间 − 第 i 条时间；
        // 即 PAID 停留 40 分钟后流转走，SHIPPED 已停留 20 分钟未离开。
        // 实体在初始状态（首次流转前）的停留不计入——历史表只从第一条流转开始
        HistoryDwellService.StateDwell paid = stats.stream()
                .filter(s -> s.state().equals("PAID")).findFirst().orElseThrow();
        assertThat(paid.samples()).isEqualTo(1);
        assertThat(paid.avgSeconds()).isEqualTo(40 * 60);

        HistoryDwellService.StateDwell shipped = stats.stream()
                .filter(s -> s.state().equals("SHIPPED")).findFirst().orElseThrow();
        assertThat(shipped.unfinishedCount()).isEqualTo(1);
        assertThat(shipped.unfinishedSeconds()).isBetween(19 * 60L, 21 * 60L);
    }

    @Test
    void 单实体统计与全量统计口径一致() {
        LocalDateTime now = LocalDateTime.now();
        seed(1, "1", "CREATED", "PAID", "PAY", now.minusMinutes(30));
        seed(2, "2", "CREATED", "CANCELLED", "CANCEL", now.minusMinutes(10));

        // 单实体只有一条流转记录：唯一被采样的是其 to_state（PAID，尚未离开）
        List<HistoryDwellService.StateDwell> entity1 = dwellService.stats("order", 1L);
        assertThat(entity1).hasSize(1);
        assertThat(entity1.get(0).state()).isEqualTo("PAID");
        assertThat(entity1.get(0).unfinishedCount()).isEqualTo(1);

        // 全量：两个实体各一段未完成停留（PAID、CANCELLED），CREATED 无采样
        List<HistoryDwellService.StateDwell> all = dwellService.stats("order");
        assertThat(all).extracting(HistoryDwellService.StateDwell::state)
                .containsExactlyInAnyOrder("PAID", "CANCELLED");
        assertThat(all.stream().mapToLong(HistoryDwellService.StateDwell::unfinishedCount).sum()).isEqualTo(2);
    }
}
