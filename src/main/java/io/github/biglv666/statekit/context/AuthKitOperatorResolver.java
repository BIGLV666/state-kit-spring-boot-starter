package io.github.biglv666.statekit.context;

import io.github.biglv666.authkit.core.AuthContext;

/**
 * auth-kit 适配的操作人解析器：直接读 {@link AuthContext}（ThreadLocal）。
 * 本类只在 auth-kit 位于类路径时被加载（autoConfiguration 类路径检测），
 * 缺 auth-kit 时不会触发 NoClassDefFoundError。
 */
public class AuthKitOperatorResolver implements OperatorResolver {

    @Override
    public String resolve() {
        return AuthContext.getUserId();
    }
}
