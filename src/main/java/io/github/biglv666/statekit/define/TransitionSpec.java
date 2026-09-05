package io.github.biglv666.statekit.define;

import java.util.Set;

/**
 * 单条流转规则：多源状态集合 --event--> 目标状态，可选挂载动作 / 守卫 bean。
 * yml 通道与 Java DSL 通道最终都产出本模型。
 */
public final class TransitionSpec {

    private final Set<String> from;
    private final String event;
    private final String to;
    private final String action;
    private final String guard;

    public TransitionSpec(Set<String> from, String event, String to, String action, String guard) {
        this.from = Set.copyOf(from);
        this.event = event;
        this.to = to;
        this.action = action;
        this.guard = guard;
    }

    /** 源状态名集合（多源简写时多于一个） */
    public Set<String> getFrom() {
        return from;
    }

    /** 事件名 */
    public String getEvent() {
        return event;
    }

    /** 目标状态名 */
    public String getTo() {
        return to;
    }

    /** 动作 bean 名，未挂载为 null */
    public String getAction() {
        return action;
    }

    /** 守卫 bean 名，未挂载为 null */
    public String getGuard() {
        return guard;
    }

    /** 供启动期校验错误信息定位使用，如 {@code [CREATED|PAID] --CANCEL--> CANCELLED} */
    @Override
    public String toString() {
        return "[" + String.join("|", from) + "] --" + event + "--> " + to;
    }
}
