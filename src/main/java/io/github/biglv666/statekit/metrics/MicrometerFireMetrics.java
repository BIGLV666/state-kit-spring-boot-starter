package io.github.biglv666.statekit.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * {@link FireMetrics} 的 micrometer 实现（0.4.0+）。注册的指标：
 *
 * <ul>
 *     <li>{@code statekit.fire}（Timer，tags: machine/event/from/to/outcome）——
 *         每次 fire 一条，count 为吞吐、percentile 为耗时；</li>
 *     <li>{@code statekit.fire.retries}（Counter，tags: machine/event）——CAS 冲突自动重试次数；</li>
 *     <li>{@code statekit.compensation}（Counter，tags: machine/event/actual/decision）——
 *         补偿策略决策分布。</li>
 * </ul>
 *
 * <p>tag 取值全部来自启动期声明的机器/事件/状态集合与 outcome 枚举，基数有界；
 * 取不到的 from/to 以 {@code "-"} 呈现。</p>
 */
public class MicrometerFireMetrics implements FireMetrics {

    private final MeterRegistry registry;

    public MicrometerFireMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(String machine, String event, String from, String to,
                       String outcome, long durationNanos) {
        Timer.builder("statekit.fire")
                .tag("machine", machine)
                .tag("event", event)
                .tag("from", orDash(from))
                .tag("to", orDash(to))
                .tag("outcome", outcome)
                .description("state-kit fire 流转次数与耗时（outcome=success/illegal/guard_rejected/"
                        + "conflict/conflict_log/compensation_retry/compensation_schedule/error）")
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordRetry(String machine, String event) {
        Counter.builder("statekit.fire.retries")
                .tag("machine", machine)
                .tag("event", event)
                .description("state-kit CAS 冲突后的自动重试次数（不含首次尝试）")
                .register(registry)
                .increment();
    }

    @Override
    public void recordCompensation(String machine, String event, String actualState, String decision) {
        Counter.builder("statekit.compensation")
                .tag("machine", machine)
                .tag("event", event)
                .tag("actual", orDash(actualState))
                .tag("decision", decision)
                .description("state-kit 冲突补偿策略决策分布（retryWith/schedule/abort）")
                .register(registry)
                .increment();
    }

    @Override
    public void recordTimerScan(String machine, String timer, long due, long fired, long durationNanos) {
        Timer.builder("statekit.timer.scan")
                .tag("machine", machine)
                .tag("timer", timer)
                .description("state-kit 停留超时扫描次数与耗时（大表压力监控指标）")
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
        if (fired > 0) {
            Counter.builder("statekit.timer.fired")
                    .tag("machine", machine)
                    .tag("timer", timer)
                    .description("state-kit 停留超时自动推进的实体数")
                    .register(registry)
                    .increment(fired);
        }
    }

    private static String orDash(String value) {
        return value == null ? "-" : value;
    }
}
