package io.github.biglv666.statekit;

/**
 * fire 的可选行为开关，经
 * {@link StateMachine#fire(Object, String, FireOptions, FireArg...)} 传入，
 * 影响单次 fire 的框架行为（不影响流转语义）。
 *
 * <pre>{@code
 * orderFlow.fire(orderId, "PAY", FireOptions.skipHistory(), set("pay_no", txnNo));
 * }</pre>
 */
public final class FireOptions {

    /** 默认行为：历史开启时正常记录 */
    public static final FireOptions DEFAULT = new FireOptions(false);

    private final boolean skipHistory;

    private FireOptions(boolean skipHistory) {
        this.skipHistory = skipHistory;
    }

    /**
     * 单次豁免历史记录：即使 {@code state-kit.history.enabled=true}，
     * 本次 fire 也不写历史表（状态照常变更）。适用于高频内部补偿流转等
     * 不想污染轨迹的场景；{@code history.enabled=false} 时本开关无效果。
     */
    public static FireOptions skipHistory() {
        return new FireOptions(true);
    }

    /** 是否豁免本次历史记录 */
    public boolean isSkipHistory() {
        return skipHistory;
    }
}
