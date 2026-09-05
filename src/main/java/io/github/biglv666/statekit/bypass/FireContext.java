package io.github.biglv666.statekit.bypass;

/**
 * fire 执行标记（ThreadLocal）：CAS 语句执行期间置位，
 * BYPASS 检测器据此区分「框架的合法 CAS」与「业务代码绕过 fire 的 status 修改」。
 * 框架内部使用，业务无需感知。
 */
public final class FireContext {

    private static final ThreadLocal<Boolean> IN_FIRE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private FireContext() {
    }

    /** 标记当前线程正在执行框架 CAS */
    public static void enter() {
        IN_FIRE.set(Boolean.TRUE);
    }

    /** 清除标记，必须在 enter 后 finally 调用 */
    public static void exit() {
        IN_FIRE.remove();
    }

    /** 当前线程是否正在执行框架 CAS */
    public static boolean inFire() {
        return Boolean.TRUE.equals(IN_FIRE.get());
    }
}
