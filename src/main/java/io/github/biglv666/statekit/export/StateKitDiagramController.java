package io.github.biglv666.statekit.export;

import io.github.biglv666.statekit.export.StateMachineExporter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 状态机可视化导出 REST 端点（0.3.0+）：仅当 spring-web 在类路径时注册。
 * 无鉴权——定位是给内部运维/文档工具消费；公网暴露请在业务网关加限制。
 */
@RestController
@RequestMapping("/statekit")
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
public class StateKitDiagramController {

    private final Map<String, StateMachineExporter> exporters;

    public StateKitDiagramController(Map<String, StateMachineExporter> exporters) {
        this.exporters = exporters;
    }

    /** GET /statekit/machines/{name}/diagram?format=mermaid（默认）|dot */
    @GetMapping(value = "/machines/{name}/diagram", produces = "text/plain;charset=UTF-8")
    public String diagram(@PathVariable String name,
                          @org.springframework.web.bind.annotation.RequestParam(defaultValue = "mermaid") String format) {
        StateMachineExporter exporter = exporters.get(name);
        if (exporter == null) {
            throw new IllegalArgumentException("状态机 [" + name + "] 不存在");
        }
        return "dot".equalsIgnoreCase(format) ? exporter.toDot() : exporter.toMermaid();
    }

    /** GET /statekit/machines/{name}/diagram/all：两种格式一次返回 */
    @GetMapping(value = "/machines/{name}/diagram/all")
    public Map<String, String> diagramAll(@PathVariable String name) {
        StateMachineExporter exporter = exporters.get(name);
        if (exporter == null) {
            throw new IllegalArgumentException("状态机 [" + name + "] 不存在");
        }
        return exporter.all();
    }
}
