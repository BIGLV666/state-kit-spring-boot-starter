package io.github.biglv666.statekit.define;

/**
 * 嵌套子状态机绑定（0.2.0+）：本机器是 parent 机器 parent-state 状态下挂的子流程。
 *
 * <p>子机器每条记录到终态后，按 {@code group-column}（子项表中指向父实体主键的列）
 * 聚合父实体下的全部子项并按策略判定：</p>
 * <ul>
 *     <li>{@code ALL}：全部子项都到终态（会签）；</li>
 *     <li>{@code ANY}：任一子项到终态即满足（或签）；</li>
 *     <li>{@code COUNT}：到终态的子项数 ≥ count 值（满 n 放行）。</li>
 * </ul>
 * 满足后框架自动对父实体 fire {@code on-complete-event}（走父机器常规 CAS 通道，
 * 冲突按父机器 conflict-strategy 处置），与子项流转同事务。
 */
public record SubMachineBinding(String parent, String parentState, String groupColumn,
                                Strategy strategy, int count, String onCompleteEvent) {

    /** 聚合策略 */
    public enum Strategy { ALL, ANY, COUNT }

    public SubMachineBinding {
        if (count < 0) {
            throw new IllegalArgumentException("count 不允许为负");
        }
    }

    public static SubMachineBinding allOf(String parent, String parentState, String groupColumn, String event) {
        return new SubMachineBinding(parent, parentState, groupColumn, Strategy.ALL, 0, event);
    }

    public static SubMachineBinding anyOf(String parent, String parentState, String groupColumn, String event) {
        return new SubMachineBinding(parent, parentState, groupColumn, Strategy.ANY, 0, event);
    }

    public static SubMachineBinding countOf(String parent, String parentState, String groupColumn,
                                            int count, String event) {
        return new SubMachineBinding(parent, parentState, groupColumn, Strategy.COUNT, count, event);
    }
}
