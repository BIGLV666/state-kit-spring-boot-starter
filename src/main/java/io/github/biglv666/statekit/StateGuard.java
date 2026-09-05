package io.github.biglv666.statekit;

/**
 * 流转守卫钩子：在 CAS 更新<b>之前</b>执行，返回 false 即拒绝本次流转
 * （抛出 {@code GuardRejectedException}），状态与 {@code set} 列均不动。
 *
 * <p>守卫只应做<b>只读校验</b>（判断余额、判断资格、判断上下文参数合法性），
 * 不要在守卫里做业务写——写操作请放在动作里，才能与状态变更同事务回滚。
 * 守卫通过后到 CAS 执行之间存在间隙，状态可能被并发修改；
 * 该间隙由 CAS 的 {@code WHERE status=from} 条件兜底，
 * 因此守卫不需要（也无法）保证绝对时序，只做最佳-effort 拦截。</p>
 *
 * <p>需要携带拒绝原因时，直接在守卫里抛
 * {@code BusinessException(自定义错误码, "余额不足")}，web-common 会原样映射；
 * 返回 false 的默认文案为 {@code GuardRejectedException} 的固定格式。</p>
 *
 * <p>实现类注册为 Spring Bean，在流转声明里以 bean 名引用：</p>
 *
 * <pre>{@code
 * @Component("orderSignGuard")
 * public class OrderSignGuard implements StateGuard<OrderStatus, Long> {
 *     public boolean test(StateTx<OrderStatus, Long> tx) {
 *         return tx.param("signer", String.class) != null;
 *     }
 * }
 * }</pre>
 *
 * @param <S>  状态枚举类型
 * @param <ID> 实体主键类型
 */
@FunctionalInterface
public interface StateGuard<S, ID> {

    /**
     * 校验是否允许本次流转。
     *
     * @param tx 流转上下文
     * @return true 放行；false 拒绝（状态不变，抛 GuardRejectedException）
     * @throws Exception 抛出异常同样视为拒绝，异常本身向外传播（可携带业务错误码）
     */
    boolean test(StateTx<S, ID> tx);
}
