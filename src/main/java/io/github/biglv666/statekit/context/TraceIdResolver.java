package io.github.biglv666.statekit.context;

import org.slf4j.MDC;

/**
 * 默认链路追踪解析器：从 MDC 读取 traceId。
 *
 * <p>api-governance 的 trace 底座是 micrometer-tracing，其日志桥接会把当前
 * traceId 写入 MDC（键默认 {@code traceId}），因此本读取器不需要依赖
 * api-governance 的任何类；未接链路组件时 MDC 无值，返回 null。</p>
 */
public class TraceIdResolver {

    private final String key;

    public TraceIdResolver(String key) {
        this.key = key;
    }

    /**
     * 解析当前链路 traceId。
     *
     * @return traceId；无链路上下文时返回 null
     */
    public String resolve() {
        return MDC.get(key);
    }
}
