package io.github.biglv666.statekit.compensation;

import java.util.Optional;
import java.util.Set;

/**
 * 冲突上下文（0.3.0+）：{@link CompensationPolicy} 判定补偿去向时的输入。
 * 策略只读，不得持有可变状态。
 *
 * @param machine     状态机名
 * @param entityId    实体主键
 * @param event       本次触发的事件
 * @param expectedFrom CAS 期望的 from 状态
 * @param actualState 冲突后重读的实际状态（可能 null：实体被删除）
 * @param allowedEvents 实际状态下允许的事件集合
 */
public record ConflictContext(String machine, Object entityId, String event,
                              String expectedFrom, String actualState,
                              Set<String> allowedEvents) {
}
