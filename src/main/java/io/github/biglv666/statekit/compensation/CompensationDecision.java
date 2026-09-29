package io.github.biglv666.statekit.compensation;

import java.util.Map;
import java.util.Optional;

/**
 * 冲突补偿决策（0.3.0+）：{@link CompensationPolicy#onConflict(ConflictContext)}
 * 返回，决定冲突后的去向。三种：
 *
 * <ul>
 *     <li>{@link #retryWith(String)}：以策略指定的新事件重跑路由/守卫/CAS（仍在同一事务）；</li>
 *     <li>{@link #abort()}：放弃并抛出 StateConflictException（等价默认行为）；</li>
 *     <li>{@link #schedule(String, long)}：不抛异常，发布
 *     {@code CompensationScheduledEvent}，由业务方自行调度延迟补偿（如定时任务/OutboxPro）。</li>
 * </ul>
 */
public sealed interface CompensationDecision {

    /** 以新事件重试（事件名，必须在实际状态的出边上） */
    record RetryWith(String event) implements CompensationDecision {
    }

    /** 放弃：抛 StateConflictException */
    record Abort() implements CompensationDecision {
    }

    /** 调度延迟补偿：发布事件，fire 正常返回 */
    record Schedule(String event, long delayMs) implements CompensationDecision {
    }

    static RetryWith retryWith(String event) {
        return new RetryWith(event);
    }

    static Abort abort() {
        return new Abort();
    }

    static Schedule schedule(String event, long delayMs) {
        return new Schedule(event, delayMs);
    }
}
