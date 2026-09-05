package io.github.biglv666.statekit.bypass;

import io.github.biglv666.statekit.define.MachineDefinition;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BYPASS 检测的受监视机器清单：Registrar 启动期把每个机器的
 * （表名、状态列、主键列、machine 名）登记进来，语句拦截器据此匹配。
 * 框架内部使用。
 */
public final class BypassWatchlist {

    /** 受监视机器的最小信息快照 */
    public record Watched(String machine, String table, String statusColumn, String idColumn) {
    }

    private final List<Watched> watched = new CopyOnWriteArrayList<>();

    /** 启动期登记机器（Registrar 调用） */
    public void register(MachineDefinition definition) {
        watched.add(new Watched(definition.getName(), definition.getTable().toLowerCase(),
                definition.getStatusColumn().toLowerCase(), definition.getIdColumn().toLowerCase()));
    }

    public List<Watched> all() {
        return List.copyOf(watched);
    }
}
