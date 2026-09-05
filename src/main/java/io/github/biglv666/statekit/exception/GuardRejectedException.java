package io.github.biglv666.statekit.exception;

import io.github.biglv666.webcommon.annotation.DefaultErrorCode;
import io.github.biglv666.webcommon.result.ResultCode;

/**
 * 守卫拒绝：{@link io.github.biglv666.statekit.StateGuard#test} 返回 false。
 *
 * <p>状态与 set 列均未发生变化。需要向调用方传达具体拒绝原因时，
 * 推荐守卫内直接抛 {@code BusinessException(自定义错误码, "原因")}，
 * 文案与错误码更可控；本异常的固定文案只适合通用兜底。</p>
 */
@DefaultErrorCode(value = ResultCode.class, constant = "BIZ_ERROR")
public class GuardRejectedException extends StateKitException {

    /** 状态机名 */
    private final transient String machine;
    /** 被拒绝的 from 状态 */
    private final transient String from;
    /** 被拒绝的事件 */
    private final transient String event;

    public GuardRejectedException(String machine, String from, String event) {
        super("流转被守卫拒绝: 状态机 [%s] 状态 [%s] 事件 [%s]".formatted(machine, from, event));
        this.machine = machine;
        this.from = from;
        this.event = event;
    }

    /** 状态机名 */
    public String getMachine() {
        return machine;
    }

    /** 被拒绝的 from 状态名 */
    public String getFrom() {
        return from;
    }

    /** 被拒绝的事件名 */
    public String getEvent() {
        return event;
    }
}
