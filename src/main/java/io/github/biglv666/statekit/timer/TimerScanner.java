package io.github.biglv666.statekit.timer;

import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.define.MachineDefinition;
import io.github.biglv666.statekit.define.TimerSpec;
import io.github.biglv666.statekit.exception.GuardRejectedException;
import io.github.biglv666.statekit.exception.IllegalTransitionException;
import io.github.biglv666.statekit.exception.StateConflictException;
import io.github.biglv666.statekit.metrics.FireMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 停留超时扫描器（0.5.0+）：按机器的 {@link TimerSpec} 声明轮询业务表，
 * 把「在 from 状态停留超过 after」的到期实体以<b>常规 fire 语义</b>自动推进——
 * 路由 / 守卫 / CAS / 动作 / 事件 / 历史 / 补偿全部生效，本类不包含任何流转逻辑。
 *
 * <p>扫描 SQL：{@code SELECT <id> FROM <table> WHERE <statusColumn> = ? AND <sinceColumn> <= ? LIMIT ?}
 * （截止时间在应用侧计算后参数绑定，跨方言一致且索引友好）。大表请为
 * {@code (status, sinceColumn)} 建组合索引——扫描成本与到期实体数成正比而非表大小；
 * 或配置 {@code state-kit.timers.scan-datasource-ref} 让扫描走从库。</p>
 *
 * <p><b>集群安全免费</b>：多实例并发扫描同一批到期实体时各自 fire，CAS 的
 * {@code WHERE status = from} 保证恰好一成一败——到期触发与手动 fire 是同一种竞争。</p>
 *
 * <p>fire 结果分类：success 正常推进；conflict / illegal 说明状态已被并发推进
 * （下一轮 WHERE 过滤自然排除，DEBUG 记录）；guard_rejected 状态未变、下轮继续
 * 重试（守卫条件满足后自动推进）；其余异常 WARN 后下轮重试，不中断扫描循环。</p>
 *
 * <p>调度：{@code state-kit.timers.polling-enabled=true}（默认）时以单线程 daemon
 * 按固定间隔轮询（{@link SmartLifecycle} 管理，不依赖 {@code @EnableScheduling}）；
 * 置为 false 时本 Bean 仍注册但不起线程，外部调度器（XXL-Job / Quartz 等）直接调用
 * {@link #scanOnce()}。</p>
 */
public class TimerScanner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TimerScanner.class);

    private final Map<String, MachinePlan> plans = new LinkedHashMap<>();
    private final StateKitTimerOptions options;
    private final FireMetrics metrics;
    private final ScheduledThreadPoolExecutor scheduler;
    private volatile boolean running;

    /**
     * @param definitions     全部机器定义（含 timers 声明的机器）
     * @param machineResolver 按机器名解析状态机 Bean（运行期 fire 用）
     * @param scanJdbc        扫描用 JdbcTemplate（可指向从库；fire 仍走各机器主库通道）
     * @param options         轮询 / 批量 / 慢扫描阈值配置
     * @param metrics         fire 指标（可为 NOOP）
     */
    public TimerScanner(Map<String, MachineDefinition> definitions,
                        java.util.function.Function<String, StateMachine<Object, Object>> machineResolver,
                        org.springframework.jdbc.core.JdbcTemplate scanJdbc,
                        StateKitTimerOptions options,
                        FireMetrics metrics) {
        this.options = options;
        this.metrics = metrics == null ? FireMetrics.NOOP : metrics;
        this.scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "statekit-timer-scanner");
            thread.setDaemon(true);
            return thread;
        });
        for (MachineDefinition definition : definitions.values()) {
            if (definition.getTimers().isEmpty()) {
                continue;
            }
            StateMachine<Object, Object> machine = machineResolver.apply(definition.getName());
            plans.put(definition.getName(), new MachinePlan(definition, machine, scanJdbc,
                    options.batchSize()));
        }
    }

    /** 是否装配了任何 timer（供装配层与测试断言） */
    public boolean hasPlans() {
        return !plans.isEmpty();
    }

    /** 单机扫描计划：一台机器的全部 timer 预编译 SQL 与 id 读取器 */
    private static final class MachinePlan {

        private final MachineDefinition definition;
        private final StateMachine<Object, Object> machine;
        private final List<TimerQuery> queries = new ArrayList<>();
        private final org.springframework.jdbc.core.JdbcTemplate scanJdbc;

        MachinePlan(MachineDefinition definition, StateMachine<Object, Object> machine,
                    org.springframework.jdbc.core.JdbcTemplate scanJdbc, int batchSize) {
            this.definition = definition;
            this.machine = machine;
            this.scanJdbc = scanJdbc;
            for (TimerSpec spec : definition.getTimers()) {
                String sql = "SELECT " + definition.getIdColumn() + " FROM " + definition.getTable()
                        + " WHERE " + definition.getStatusColumn() + " = ? AND " + spec.sinceColumn()
                        + " <= ? LIMIT " + batchSize;
                queries.add(new TimerQuery(spec, sql, idMapper(definition.getIdType())));
            }
        }

        /** 单条 timer 的到期实体 id 列表（按机器 id-type 读取） */
        List<Object> dueIds(TimerQuery query, Timestamp deadline) {
            return scanJdbc.query(query.sql(), query.idMapper(), query.spec().from(), deadline);
        }

        private static org.springframework.jdbc.core.RowMapper<Object> idMapper(Class<?> idType) {
            return (rs, rowNum) -> {
                if (idType == String.class) {
                    return rs.getString(1);
                }
                if (idType == Integer.class || idType == int.class) {
                    return rs.getInt(1);
                }
                if (idType == Long.class || idType == long.class) {
                    return rs.getLong(1);
                }
                return rs.getObject(1);
            };
        }
    }

    private record TimerQuery(TimerSpec spec, String sql,
                              org.springframework.jdbc.core.RowMapper<Object> idMapper) {
    }

    /** 手动触发一轮全量扫描（外部调度器入口）：逐机逐 timer 扫描并 fire 到期实体。
     *
     * @return 本轮成功推进的实体总数；所有到期实体已处理完时为 0
     */
    public int scanOnce() {
        int fired = 0;
        for (Map.Entry<String, MachinePlan> entry : plans.entrySet()) {
            fired += scanMachine(entry.getKey(), entry.getValue());
        }
        return fired;
    }

    /** 单机一轮扫描：逐 timer 清空到期队列并 fire，返回成功推进数。
     *
     * <p>每个 timer 的扫描是「批量 + 有进展才继续」的排水循环：单次 SELECT 取满
     * batchSize 且本轮有 fire 成功时才再次查询（已推进实体离开结果集）；
     * 整批守卫拒绝等无进展场景立即停止，天然防死循环。守卫拒绝的实体由下一轮
     * 轮询重试。</p>
     */
    private int scanMachine(String machineName, MachinePlan plan) {
        long begin = System.nanoTime();
        int fired = 0;
        for (TimerQuery query : plan.queries) {
            int due = 0;
            int firedForTimer = 0;
            while (true) {
                Timestamp deadline = Timestamp.from(Instant.now().minus(query.spec().after()));
                List<Object> ids;
                try {
                    ids = plan.dueIds(query, deadline);
                } catch (Exception e) {
                    // 扫描 SQL 本身失败（如列缺失、数据库抖动）：本轮该 timer 跳过，下轮继续
                    log.error("状态机 [{}] 超时扫描查询失败（from={}, since-column={}）",
                            machineName, query.spec().from(), query.spec().sinceColumn(), e);
                    break;
                }
                due += ids.size();
                int firedInRound = 0;
                for (Object id : ids) {
                    try {
                        plan.machine.fire(id, query.spec().event());
                        fired++;
                        firedForTimer++;
                        firedInRound++;
                    } catch (GuardRejectedException e) {
                        // 守卫未放行：状态未变，下一轮继续尝试（条件满足后自动推进）
                        log.debug("状态机 [{}] 超时触发实体 [{}] 事件 [{}] 被守卫拒绝，下轮重试",
                                machineName, id, query.spec().event());
                    } catch (StateConflictException | IllegalTransitionException e) {
                        // 状态已被并发推进：下一轮 WHERE 过滤自然排除
                        log.debug("状态机 [{}] 超时触发实体 [{}] 事件 [{}] 状态已变化: {}",
                                machineName, id, query.spec().event(), e.getMessage());
                    } catch (Exception e) {
                        // 动作等业务异常：状态未变，下轮重试；WARN 便于发现持续失败
                        log.warn("状态机 [{}] 超时触发实体 [{}] 事件 [{}] 失败，下轮重试",
                                machineName, id, query.spec().event(), e);
                    }
                }
                // 结果不满一批（已清空）或本轮零推进（整批被拒/失败，继续查询只会拿到同一批）：停止
                if (ids.size() < options.batchSize() || firedInRound == 0) {
                    break;
                }
            }
            metrics.recordTimerScan(machineName,
                    query.spec().from() + "@" + query.spec().event(),
                    due, firedForTimer, System.nanoTime() - begin);
        }
        long elapsed = System.nanoTime() - begin;
        if (elapsed > options.slowScanThreshold().toNanos()) {
            log.warn("状态机 [{}] 超时扫描耗时 {}ms 超过阈值 {}ms——大表请为 (status, since-column) "
                            + "建组合索引，或配置 state-kit.timers.scan-datasource-ref 走从库",
                    machineName, TimeUnit.NANOSECONDS.toMillis(elapsed),
                    options.slowScanThreshold().toMillis());
        }
        return fired;
    }

    // ---- SmartLifecycle：内置轮询调度 ----

    @Override
    public void start() {
        if (!options.pollingEnabled()) {
            log.info("state-kit 停留超时轮询已关闭（timers.polling-enabled=false），"
                    + "请由外部调度器调用 TimerScanner#scanOnce()");
            return;
        }
        Duration interval = options.pollInterval();
        scheduler.scheduleWithFixedDelay(this::safeScanAll, interval.toMillis(),
                interval.toMillis(), TimeUnit.MILLISECONDS);
        running = true;
        log.info("state-kit 停留超时轮询已启动：间隔 {}ms，批量 {}，覆盖机器 {}",
                interval.toMillis(), options.batchSize(), plans.keySet());
    }

    @Override
    public void stop() {
        running = false;
        scheduler.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /** 轮询循环体：任何异常不中断调度 */
    private void safeScanAll() {
        try {
            scanOnce();
        } catch (Throwable e) {
            log.error("state-kit 停留超时扫描轮次异常", e);
        }
    }

    /** 扫描器运行参数（0.5.0+），来自 state-kit.timers.* 配置 */
    public record StateKitTimerOptions(boolean pollingEnabled, Duration pollInterval,
                                       int batchSize, Duration slowScanThreshold) {

        public StateKitTimerOptions {
            if (batchSize <= 0) {
                throw new IllegalArgumentException("state-kit.timers.batch-size 必须为正整数: " + batchSize);
            }
            if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
                throw new IllegalArgumentException("state-kit.timers.poll-interval 必须为正时长");
            }
            if (slowScanThreshold == null || slowScanThreshold.isNegative()) {
                slowScanThreshold = Duration.ofSeconds(1);
            }
        }
    }
}
