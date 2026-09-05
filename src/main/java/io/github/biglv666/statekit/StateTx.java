package io.github.biglv666.statekit;

/**
 * 一次流转的上下文，传入守卫（{@link StateGuard}）与动作（{@link StateAction}）。
 *
 * <p>守卫与动作执行时，CAS 已确认本事务持有该行行锁（或 CAS 尚未发生——守卫阶段），
 * 因此动作内的业务写操作与状态变更天然处于同一事务，安全且原子。</p>
 *
 * <p>注意 {@link #param(String, Class)} 只能读到 {@link FireArg#param(String, Object)}
 * 声明的上下文；{@link FireArg#set(String, Object)} 声明的落库列不会出现在这里。</p>
 *
 * @param <S>  状态枚举类型
 * @param <ID> 实体主键类型
 */
public interface StateTx<S, ID> {

    /**
     * 流转目标实体主键。
     */
    ID entityId();

    /**
     * 本次流转的事件名，如 "PAY"。
     */
    String event();

    /**
     * 流转前状态（来自数据库实读，而非调用方假设）。
     */
    S from();

    /**
     * 流转目标状态。
     */
    S to();

    /**
     * 读取 {@link FireArg#param(String, Object)} 声明的上下文参数并按给定类型转换。
     *
     * @param key  参数名
     * @param type 期望类型
     * @param <T>  期望类型
     * @return 参数值；未声明时返回 null
     * @throws ClassCastException 参数实际类型与期望类型不符
     */
    <T> T param(String key, Class<T> type);

    /**
     * 当前登录用户标识。类路径存在 auth-kit 时取自 {@code AuthContext}，否则为 null。
     * 可通过 {@code state-kit.operator=none} 强制关闭。
     */
    String operatorId();

    /**
     * 当前请求链路 traceId。取自 MDC（键名默认 "traceId"，可配）， micrometer-tracing
     * 接入后由 api-governance 等链路组件自动填充，无链路上下文时为 null。
     */
    String traceId();
}
