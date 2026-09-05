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
}
