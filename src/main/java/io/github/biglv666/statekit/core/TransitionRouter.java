package io.github.biglv666.statekit.core;

import io.github.biglv666.statekit.define.TransitionSpec;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 路由表：启动期由状态机定义编译而成的 from → event → 规则 二级映射。
 * 同一 event 可从多个状态触发（各边独立），路由时按<b>实读的当前状态</b>定位唯一边，
 * 因而不存在"同一 event 多边"的路由歧义；边与边之间按 (from, event) 启动期去重。
 */
public final class TransitionRouter {

    private final Map<String, Map<String, TransitionSpec>> edges = new HashMap<>();
    /** from → 直接后继状态集合 */
    private final Map<String, Set<String>> nextStates = new HashMap<>();

    /** 编译路由表；调用前由启动期校验保证 (from, event) 无重复 */
    public void compile(java.util.List<TransitionSpec> transitions) {
        for (TransitionSpec spec : transitions) {
            for (String from : spec.getFrom()) {
                TransitionSpec prev = edges
                        .computeIfAbsent(from, k -> new HashMap<>())
                        .put(spec.getEvent(), spec);
                if (prev != null) {
                    // 理论上不可达：启动期校验已拦截重复 (from, event)
                    throw new IllegalStateException("流转规则重复: " + prev + " 与 " + spec);
                }
                nextStates.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(spec.getTo());
            }
        }
    }

    /** 查边：当前状态 + 事件 → 唯一规则；无出边返回 empty */
    public Optional<TransitionSpec> route(String from, String event) {
        return Optional.ofNullable(edges.get(from)).map(m -> m.get(event));
    }

    /** 当前状态允许触发的事件集合 */
    public Set<String> allowedEvents(String from) {
        Map<String, TransitionSpec> m = edges.get(from);
        return m == null ? Set.of() : Set.copyOf(m.keySet());
    }

    /** 状态的直接后继状态名集合 */
    public Set<String> nextStates(String from) {
        return Set.copyOf(nextStates.getOrDefault(from, Set.of()));
    }

    /** 状态是否为终态（无任何出边） */
    public boolean isFinal(String from) {
        return !edges.containsKey(from);
    }
}
