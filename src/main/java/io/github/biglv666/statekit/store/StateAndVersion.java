package io.github.biglv666.statekit.store;

/**
 * 状态 + 乐观锁版本的读取快照（version-column 启用时使用）。
 */
public record StateAndVersion(String state, Object version) {

    /** 仅状态、无版本信息 */
    public static StateAndVersion of(String state) {
        return new StateAndVersion(state, null);
    }
}
