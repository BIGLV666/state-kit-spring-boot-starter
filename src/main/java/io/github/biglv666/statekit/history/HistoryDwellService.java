package io.github.biglv666.statekit.history;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 状态停留时长统计（0.2.0+）：基于历史表相邻流转记录计算各状态的停留区间。
 *
 * <p>算法：按实体分组、时间升序排列后，第 i 条记录的 {@code to_state} 停留时长
 * = 第 i+1 条记录时间 − 第 i 条记录时间；最后一条记录的停留 = 当前时间 − 记录时间
 * （尚未流转离开，计入 unfinished）。仅当 {@code state-kit.history.enabled=true} 时装配。</p>
 */
public class HistoryDwellService {

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;

    public HistoryDwellService(JdbcTemplate jdbcTemplate, String tableName) {
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
    }

    /**
     * 统计某状态机全部实体的状态停留分布。
     *
     * @param machine 状态机名
     * @return 各状态停留统计（按平均停留时长降序）
     */
    public List<StateDwell> stats(String machine) {
        List<Row> rows = jdbcTemplate.query("""
                        SELECT entity_id, to_state, create_time FROM %s
                        WHERE machine = ? ORDER BY entity_id ASC, create_time ASC, id ASC"""
                        .formatted(tableName),
                (rs, i) -> new Row(rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3).toLocalDateTime()),
                machine);
        return aggregate(rows);
    }

    /**
     * 统计单个实体的状态停留分布。
     *
     * @param machine  状态机名
     * @param entityId 实体主键
     * @return 各状态停留统计（按平均停留时长降序）
     */
    public List<StateDwell> stats(String machine, Object entityId) {
        List<Row> rows = jdbcTemplate.query("""
                        SELECT entity_id, to_state, create_time FROM %s
                        WHERE machine = ? AND entity_id = ? ORDER BY create_time ASC, id ASC"""
                        .formatted(tableName),
                (rs, i) -> new Row(rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3).toLocalDateTime()),
                machine, String.valueOf(entityId));
        return aggregate(rows);
    }

    private List<StateDwell> aggregate(List<Row> rows) {
        Map<String, Accum> byState = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        // rows 已按实体分组、时间升序：相邻同实体记录构成停留区间
        for (int i = 0; i < rows.size(); i++) {
            Row current = rows.get(i);
            Row next = i + 1 < rows.size() ? rows.get(i + 1) : null;
            boolean sameEntity = next != null && next.entityId.equals(current.entityId);
            long seconds = sameEntity
                    ? Math.max(0, Duration.between(current.time, next.time).getSeconds())
                    // 实体最后一段：至今未离开，单独计入未完成停留
                    : -1;
            Accum acc = byState.computeIfAbsent(current.toState, k -> new Accum(k));
            if (seconds >= 0) {
                acc.sample(seconds);
            } else {
                acc.unfinished(Duration.between(current.time, now).getSeconds());
            }
        }
        List<StateDwell> result = new ArrayList<>();
        for (Accum a : byState.values()) {
            result.add(a.build());
        }
        result.sort(Comparator.comparingDouble(StateDwell::avgSeconds).reversed());
        return result;
    }

    /** 单状态停留统计 */
    public record StateDwell(String state, long samples, double avgSeconds,
                             long maxSeconds, long unfinishedSeconds, int unfinishedCount) {
    }

    private record Row(String entityId, String toState, LocalDateTime time) {
    }

    private static final class Accum {
        private final String state;
        private long samples;
        private long total;
        private long max;
        private long unfinishedSeconds;
        private int unfinishedCount;

        private Accum(String state) {
            this.state = state;
        }

        private void sample(long seconds) {
            samples++;
            total += seconds;
            max = Math.max(max, seconds);
        }

        private void unfinished(long seconds) {
            unfinishedCount++;
            unfinishedSeconds += seconds;
        }

        private StateDwell build() {
            double avg = samples == 0 ? 0 : (double) total / samples;
            return new StateDwell(state, samples, avg, max, unfinishedSeconds, unfinishedCount);
        }
    }
}
