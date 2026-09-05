package io.github.biglv666.statekit.define;

/**
 * 冲突自动重试策略（0.2.0+）：CAS 未命中后，事务内重读状态 → 重解析路由 →
 * 重跑守卫 → 再 CAS；动作只在成功后执行一次。重试耗尽仍未命中按
 * conflict-strategy 处置（throw 抛异常 / log 记日志返回）。
 *
 * <p>仅在 {@code conflict-strategy=throw} 的机器上生效；典型场景是
 * 「以最新状态为准的补偿性流转」——例如重试后实体已被他人推进到下一状态，
 * 重解析可能命中新的出边继续流转，也可能因边不存在直接抛非法流转。</p>
 */
public record RetryPolicy(int maxAttempts, long backoffMs) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max-attempts 至少为 1（1 = 不重试）");
        }
        if (backoffMs < 0) {
            throw new IllegalArgumentException("backoff-ms 不允许为负");
        }
    }

    /** 不重试 */
    public static RetryPolicy none() {
        return new RetryPolicy(1, 0);
    }

    /** 总尝试次数（含首次） */
    public int totalAttempts() {
        return maxAttempts;
    }
}
