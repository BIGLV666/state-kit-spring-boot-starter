package io.github.biglv666.statekit.testapp;

import io.github.biglv666.statekit.event.StateTransitedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用事件收集器：验证 StateTransitedEvent 的发布与字段。
 */
@Component
public class TestEventCollector {

    public final List<StateTransitedEvent> events = new CopyOnWriteArrayList<>();

    @EventListener
    public void on(StateTransitedEvent event) {
        events.add(event);
    }

    public void reset() {
        events.clear();
    }
}
