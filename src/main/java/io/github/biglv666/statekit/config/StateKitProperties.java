package io.github.biglv666.statekit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * state-kit 配置总览（全部有默认值，零配置场景只需声明 machines.transitions）：
 *
 * <pre>{@code
 * state-kit:
 *   history:
 *     enabled: false                  # 默认关：零 DDL、零历史 Bean
 *     table-name: sk_transition_history
 *   operator: auto                    # auto=类路径有 auth-kit 就取 AuthContext；none=强制空
 *   trace:
 *     key: traceId                    # 从 MDC 取 traceId 的键名
 *   machines:
 *     order:
 *       state-type: com.demo.OrderStatus
 *       id-type: java.lang.Long       # 默认 Long
 *       table: t_order
 *       status-column: status         # 默认 status
 *       id-column: id                 # 默认 id
 *       conflict-strategy: throw      # throw / log
 *       transitions:
 *         - { from: CREATED, event: PAY, to: PAID }
 * }</pre>
 */
@ConfigurationProperties(prefix = "state-kit")
public class StateKitProperties {

    /** 历史记录配置，见 {@link HistoryProperties} */
    private final HistoryProperties history = new HistoryProperties();

    /**
     * 操作人填充模式：{@code auto}（默认）= 类路径存在 auth-kit 时从
     * {@code AuthContext.getUserId()} 取，未登录为 null；{@code none} = 强制关闭。
     */
    private String operator = "auto";

    /** 链路追踪配置，见 {@link TraceProperties} */
    private final TraceProperties trace = new TraceProperties();

    /** BYPASS 绕改检测配置，见 {@link BypassProperties}（0.2.0+） */
    private final BypassProperties bypass = new BypassProperties();

    /** 可观测性指标配置，见 {@link MetricsProperties}（0.4.0+） */
    private final MetricsProperties metrics = new MetricsProperties();

    /** 停留超时扫描配置，见 {@link TimersProperties}（0.5.0+） */
    private final TimersProperties timers = new TimersProperties();

    /** 状态机声明，key 为状态机名（即 Bean 名） */
    private Map<String, MachineProperties> machines = new LinkedHashMap<>();

    public HistoryProperties getHistory() {
        return history;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public TraceProperties getTrace() {
        return trace;
    }

    public BypassProperties getBypass() {
        return bypass;
    }

    public MetricsProperties getMetrics() {
        return metrics;
    }

    public TimersProperties getTimers() {
        return timers;
    }

    public Map<String, MachineProperties> getMachines() {
        return machines;
    }

    public void setMachines(Map<String, MachineProperties> machines) {
        this.machines = machines;
    }

    /** 历史记录配置：默认完全关闭（零建表、零写入、零 Bean），显式开启才自动建表 */
    public static class HistoryProperties {

        /**
         * 是否开启流转历史。默认 {@code false}：启动不做任何 DDL、不注册任何历史相关 Bean。
         * 显式设为 {@code true} 后：启动检查并自动创建历史表（已存在则跳过），
         * 每次 fire 在业务同一事务写入流转记录。
         */
        private boolean enabled = false;

        /** 历史表名 */
        private String tableName = "sk_transition_history";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }
    }

    /** 链路追踪配置 */
    public static class TraceProperties {

        /**
         * 从 MDC 读取 traceId 的键名。micrometer-tracing（api-governance 的 trace 底座）
         * 默认把 traceId 放在 MDC 的 "traceId" 键下；自定义链路实现可通过本键适配。
         */
        private String key = "traceId";

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    /**
     * BYPASS 绕改检测配置（0.2.0+）：拦截绕过 fire 直接 UPDATE 业务表 status 列的语句。
     * 默认 off——老用户升级零行为变化；开启后通过装饰容器 DataSource 实现，零业务侵入。
     */
    public static class BypassProperties {

        /**
         * 检测模式：{@code off}（默认，不启用）；{@code log} = 命中仅 WARN 日志；
         * {@code event} = WARN 日志 + 发布 {@code StateBypassDetectedEvent}。
         */
        private String mode = "off";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }

    /**
     * 可观测性指标配置（0.4.0+）：fire 结果/耗时、重试与补偿决策的 micrometer 指标。
     * 仅当类路径存在 micrometer-core 且容器有 {@code MeterRegistry} 时才真正生效，
     * 否则一律 NOOP（零开销）——本开关用于在生效环境下强制关闭。
     */
    public static class MetricsProperties {

        /**
         * 是否启用 fire 指标。默认 {@code true}（类路径生效时）；设为 {@code false} 强制 NOOP。
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 停留超时扫描配置（0.5.0+）：仅当任一机器声明 timers 时才有实际作用。
     * 大表调优（索引 / 间隔 / 批量 / 从库）见 README 0.5.0 章节。
     */
    public static class TimersProperties {

        /**
         * 是否启用内置轮询。默认 {@code true}（单线程 daemon 按固定间隔扫描）；
         * {@code false} 时不起调度线程，由外部调度器（XXL-Job / Quartz 等）调用
         * {@code TimerScanner#scanOnce()}。
         */
        private boolean pollingEnabled = true;

        /** 轮询间隔，默认 30s */
        private java.time.Duration pollInterval = java.time.Duration.ofSeconds(30);

        /** 单条扫描 SQL 的 LIMIT 上限，默认 200；配合 (status, since-column) 索引控制单次扫描压力 */
        private int batchSize = 200;

        /**
         * 慢扫描告警阈值，默认 1s；超过时 WARN 并提示建组合索引或改走从库。
         */
        private java.time.Duration slowScanThreshold = java.time.Duration.ofSeconds(1);

        /**
         * 扫描专用数据源 bean 名（0.5.0+，可选）：配置后扫描 SQL 走该数据源
         * （典型为读写分离的从库），fire 仍走各机器的主库通道。留空用主数据源。
         */
        private String scanDatasourceRef;

        public boolean isPollingEnabled() {
            return pollingEnabled;
        }

        public void setPollingEnabled(boolean pollingEnabled) {
            this.pollingEnabled = pollingEnabled;
        }

        public java.time.Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(java.time.Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public java.time.Duration getSlowScanThreshold() {
            return slowScanThreshold;
        }

        public void setSlowScanThreshold(java.time.Duration slowScanThreshold) {
            this.slowScanThreshold = slowScanThreshold;
        }

        public String getScanDatasourceRef() {
            return scanDatasourceRef;
        }

        public void setScanDatasourceRef(String scanDatasourceRef) {
            this.scanDatasourceRef = scanDatasourceRef;
        }
    }
}
