package io.github.biglv666.statekit.metrics;

import io.github.biglv666.statekit.core.MachineRuntime;
import io.github.biglv666.statekit.define.MachineDefinition;
import io.github.biglv666.statekit.define.TransitionSpec;
import io.github.biglv666.statekit.export.StateMachineExporter;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * state-kit Actuator 端点（0.4.0+）：{@code GET /actuator/statekit} 全部机器摘要、
 * {@code GET /actuator/statekit/{machine}} 单机详情（含 Mermaid 图文本）。
 * 随 Actuator 端点体系统一暴露与鉴权（management.endpoints.web.exposure.include、
 * Spring Security 端点级管控）；0.3.0 的 {@code /statekit/**} REST 端点保留不变。
 *
 * <p>类路径无 actuator 时本类不被加载（见观测自动装配的类级守卫）。</p>
 */
@Endpoint(id = "statekit")
public class StateKitEndpoint {

    private final Map<String, StateMachineExporter> exporters;

    public StateKitEndpoint(Map<String, StateMachineExporter> exporters) {
        this.exporters = exporters;
    }

    /** 全部机器摘要 */
    @ReadOperation
    public Map<String, Object> machines() {
        Map<String, Object> result = new LinkedHashMap<>();
        exporters.forEach((name, exporter) -> result.put(name, summary(exporter)));
        return result;
    }

    /** 单机详情：摘要 + Mermaid 状态图文本 */
    @ReadOperation
    public Map<String, Object> machine(@Selector String name) {
        StateMachineExporter exporter = exporters.get(name);
        if (exporter == null) {
            throw new IllegalArgumentException("状态机 [" + name + "] 不存在");
        }
        Map<String, Object> result = summary(exporter);
        result.put("mermaid", exporter.toMermaid());
        return result;
    }

    private Map<String, Object> summary(StateMachineExporter exporter) {
        MachineRuntime<?> runtime = exporter.runtime();
        MachineDefinition def = runtime.getDefinition();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", def.getName());
        summary.put("stateType", def.getStateType().getName());
        summary.put("idType", def.getIdType().getName());
        summary.put("table", def.getTable());
        summary.put("statusColumn", def.getStatusColumn());
        summary.put("idColumn", def.getIdColumn());
        summary.put("versionColumn", def.getVersionColumn());
        summary.put("reactive", def.isReactive());
        summary.put("conflictStrategy", def.getConflictStrategy() == null ? null : def.getConflictStrategy().name());
        summary.put("retry", def.getRetry());
        summary.put("compensation", def.getCompensation());
        summary.put("sub", def.getSubBinding());
        summary.put("finalStates", runtime.finalStates());
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (TransitionSpec spec : def.getTransitions()) {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("from", spec.getFrom());
            edge.put("event", spec.getEvent());
            edge.put("to", spec.getTo());
            edge.put("guard", spec.getGuard());
            edge.put("action", spec.getAction());
            edge.put("description", spec.getDescription());
            transitions.add(edge);
        }
        summary.put("transitions", transitions);
        return summary;
    }
}
