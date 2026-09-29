package io.github.biglv666.statekit.compensation;

/**
 * 冲突补偿策略 SPI（0.3.0+）：fire 的 CAS 冲突（含自动重试耗尽）后调用，
 * 决定冲突后的去向。实现类注册为 Spring Bean，机器声明
 * {@code compensation: beanName} 引用。
 *
 * <p>设计定位：让"冲突后怎么办"从业务 try-catch 里收敛为可声明的策略。
 * 典型实现：</p>
 * <pre>{@code
 * @Component("orderCompensation")
 * public class OrderCompensation implements CompensationPolicy {
 *     public CompensationDecision onConflict(ConflictContext ctx) {
 *         // 已被并发推进到 PAID：直接补发 SHIP 而不是抛异常
 *         if ("PAID".equals(ctx.actualState())) {
 *             return CompensationDecision.retryWith("SHIP");
 *         }
 *         // 排队延迟补偿
 *         if ("CREATED".equals(ctx.actualState())) {
 *             return CompensationDecision.schedule("PAY", 30_000);
 *         }
 *         return CompensationDecision.abort();
 *     }
 * }
 * }</pre>
 *
 * <p>约束：策略自身负责终止性——{@code retryWith} 会进入新的完整 fire 流程，
 * 若仍冲突将再次回调本策略，框架加 3 次硬上限防失控，超限按 abort 处理。</p>
 */
@FunctionalInterface
public interface CompensationPolicy {

    /**
     * 冲突判定。
     *
     * @param ctx 冲突上下文（重读后的实际状态与可用事件）
     * @return 补偿决策，不允许返回 null（返回 null 按 abort 处理）
     */
    CompensationDecision onConflict(ConflictContext ctx);
}
