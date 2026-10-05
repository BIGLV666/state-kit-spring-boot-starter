package io.github.biglv666.statekit.export;

import io.github.biglv666.statekit.core.MachineRuntime;
import io.github.biglv666.statekit.define.MachineDefinition;
import io.github.biglv666.statekit.define.TransitionSpec;

import java.util.Map;

/**
 * 状态机可视化导出（0.3.0+）：把机器声明渲染为 Mermaid / DOT 文本，
 * 前端或文档工具直接消费。随每台机器装配一个实例（bean 名 = machine名 + Exporter）。
 */
public class StateMachineExporter {

    private final MachineRuntime<?> runtime;

    public StateMachineExporter(MachineRuntime<?> runtime) {
        this.runtime = runtime;
    }

    /** 所属机器运行时（0.4.0+ 供 Actuator 端点读取机器定义） */
    public MachineRuntime<?> runtime() {
        return runtime;
    }

    /**
     * 导出为 Mermaid stateDiagram-v2 文本。
     * 终态标 [*] 双向连线，边上标注 event，action/guard 以注释角标呈现。
     */
    public String toMermaid() {
        MachineDefinition def = runtime.getDefinition();
        StringBuilder sb = new StringBuilder("stateDiagram-v2\n");
        sb.append("    %% machine: ").append(def.getName()).append("\n");

        for (TransitionSpec spec : def.getTransitions()) {
            for (String from : spec.getFrom()) {
                sb.append("    ").append(quote(from)).append(" --> ").append(quote(spec.getTo()))
                        .append(" : ").append(spec.getEvent());
                if (spec.getAction() != null) {
                    sb.append(" [action: ").append(spec.getAction()).append(']');
                }
                if (spec.getGuard() != null) {
                    sb.append(" [guard: ").append(spec.getGuard()).append(']');
                }
                sb.append('\n');
            }
        }
        // 终态标识
        for (String finalState : runtime.finalStates()) {
            sb.append("    ").append(quote(finalState)).append(" --> [*]\n");
        }
        return sb.toString();
    }

    /**
     * 导出为 Graphviz DOT 文本。
     */
    public String toDot() {
        MachineDefinition def = runtime.getDefinition();
        StringBuilder sb = new StringBuilder();
        sb.append("digraph ").append(def.getName()).append(" {\n");
        sb.append("    rankdir=LR;\n");
        sb.append("    node [shape=circle];\n");

        for (TransitionSpec spec : def.getTransitions()) {
            for (String from : spec.getFrom()) {
                sb.append("    \"").append(from).append("\" -> \"").append(spec.getTo())
                        .append("\" [label=\"").append(spec.getEvent());
                if (spec.getAction() != null) {
                    sb.append(" / a:").append(spec.getAction());
                }
                if (spec.getGuard() != null) {
                    sb.append(" / g:").append(spec.getGuard());
                }
                sb.append("\"];\n");
            }
        }
        for (String finalState : runtime.finalStates()) {
            sb.append("    \"").append(finalState).append("\" [shape=doublecircle];\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String quote(String state) {
        // Mermaid 状态名含特殊字符时用引号包裹；枚举 name() 是合法标识符，直接放行
        return state.matches("^[A-Za-z0-9_]+$") ? state : '"' + state + '"';
    }

    /** 全部格式（便于前端一次拉取） */
    public Map<String, String> all() {
        return Map.of("mermaid", toMermaid(), "dot", toDot());
    }
}
