package io.github.biglv666.statekit.define;

import java.time.Duration;

/**
 * 停留超时自动流转声明（0.5.0+）：实体在 {@code from} 状态停留超过 {@code after}
 * 后，由扫描器以常规 fire 语义触发 {@code event}（路由 / 守卫 / CAS / 动作 / 历史 /
 * 补偿全部生效）。
 *
 * <p>到期判定基于业务表时间列扫描：{@code WHERE status = from AND sinceColumn <= now - after}。
 * 因此 {@code sinceColumn} 必须只在实体<b>进入该状态</b>时更新——推荐 create_time
 * 或专用的状态时间列；若使用随任意字段刷新的 update_time，停留计时会被错误重置。</p>
 *
 * <p>大表请为 {@code (status, sinceColumn)} 建组合索引（扫描成本与到期实体数成正比，
 * 与表大小无关），或配置 {@code state-kit.timers.scan-datasource-ref} 让扫描走从库。</p>
 *
 * @param from        触发条件状态（实体当前停留的状态）
 * @param after       停留时长阈值，必须为正
 * @param event       到期后触发的事件，必须是 from 状态的合法出边
 * @param sinceColumn 业务表中"进入该状态时间"的列名（合法 SQL 标识符）
 */
public record TimerSpec(String from, Duration after, String event, String sinceColumn) {
}
