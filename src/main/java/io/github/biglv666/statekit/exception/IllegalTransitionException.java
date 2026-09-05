package io.github.biglv666.statekit.exception;

import io.github.biglv666.webcommon.annotation.DefaultErrorCode;
import io.github.biglv666.webcommon.result.ResultCode;

import java.util.Set;

/**
 * 非法流转：当前状态没有该事件的出边（状态图不允许），或事件本身未声明。
 *
 * <p>这是<b>确定性</b>错误——不是并发问题，重试也不会成功，
 * 说明调用方对状态机的假设错了。与 {@link StateConflictException} 的区别：
 * 后者是并发窗口里的竞态，重试可能成功。</p>
 */
@DefaultErrorCode(value = ResultCode.class, constant = "CONFLICT")
public class IllegalTransitionException extends StateKitException {

    /** 状态机名 */
    private final transient String machine;
    /** 发起流转时的状态；实体不存在时为 null */
    private final transient String from;
    /** 触发的事件 */
    private final transient String event;

    /**
     * @param machine 状态机名
     * @param from    当前状态名，实体不存在时为 null
     * @param event   事件名
     * @param allowed 当前状态下允许的事件集合，用于错误提示；from 为 null 时为空集
     */
    public IllegalTransitionException(String machine, String from, String event, Set<String> allowed) {
        super(buildMessage(machine, from, event, allowed));
        this.machine = machine;
        this.from = from;
        this.event = event;
    }

    private static String buildMessage(String machine, String from, String event, Set<String> allowed) {
        if (from == null) {
            return "状态流转失败: 状态机 [%s] 中实体不存在或状态列不可读, event=%s".formatted(machine, event);
        }
        return "状态流转失败: 状态机 [%s] 不允许在状态 [%s] 上触发事件 [%s]%s"
                .formatted(machine, from, event,
                        allowed == null || allowed.isEmpty() ? "（当前状态无任何出边，已是终态）"
                                : "，当前允许的事件: " + allowed);
    }

    /** 状态机名 */
    public String getMachine() {
        return machine;
    }

    /** 发起流转时的状态名，实体不存在时为 null */
    public String getFrom() {
        return from;
    }

    /** 触发的事件名 */
    public String getEvent() {
        return event;
    }
}
