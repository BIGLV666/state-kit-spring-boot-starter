package io.github.biglv666.statekit;

/**
 * 流转动作钩子：在 CAS 更新成功之后执行，与状态变更处于同一事务——
 * 动作内抛出任何异常，整体回滚，状态不变、{@code set} 列不变、历史记录不写。
 *
 * <p>动作里的业务写操作（如更新订单的发货信息）与 CAS 共持同一行行锁，
 * 并发访问该行天然被阻塞到事务提交，无需额外加锁。</p>
 *
 * <p>实现类注册为 Spring Bean，在流转声明里以 bean 名引用：</p>
 *
 * <pre>{@code
 * @Component("orderSignAction")
 * public class OrderSignAction implements StateAction<OrderStatus, Long> {
 *     public void execute(StateTx<OrderStatus, Long> tx) {
 *         orderMapper.updateSignInfo(tx.entityId(), LocalDateTime.now());
 *     }
 * }
 * }</pre>
 *
 * <p>动作内<b>禁止</b>修改 status 列——status 的唯一写入口是框架的 CAS
 * （铁律，见 README「状态唯一写入口」一节）。</p>
 *
 * @param <S>  状态枚举类型
 * @param <ID> 实体主键类型
 */
@FunctionalInterface
public interface StateAction<S, ID> {

    /**
     * 执行流转后动作。
     *
     * @param tx 流转上下文，含实体 id、事件、前后状态、param 上下文与 operator/trace
     * @throws Exception 抛出任何异常（含 RuntimeException）都将导致本次 fire 整体回滚
     */
    void execute(StateTx<S, ID> tx);
}
