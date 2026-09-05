package io.github.biglv666.statekit.context;

/**
 * 操作人解析 SPI：fire 时填充 {@code StateTx.operatorId()} 与历史表 operator_id 列。
 *
 * <p>默认装配：类路径存在 auth-kit 时解析 {@code AuthContext.getUserId()}（未登录为 null），
 * 否则恒为 null。业务有自建登录态时实现本接口注册为 Bean 即可替换。</p>
 */
@FunctionalInterface
public interface OperatorResolver {

    /**
     * 解析当前操作人标识。
     *
     * @return 操作人标识；无法解析（未登录、非请求线程、未接入）时返回 null
     */
    String resolve();
}
