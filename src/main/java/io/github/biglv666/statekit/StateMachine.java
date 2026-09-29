package io.github.biglv666.statekit;

import io.github.biglv666.statekit.define.DefinitionBuilder;

import java.util.Optional;
import java.util.Set;

/**
 * 状态机统一入口，由框架启动时按流转声明动态生成并注册为 Spring Bean
 * （bean 名 = 状态机名），业务直接按泛型注入使用：
 *
 * <pre>{@code
 * @Service
 * @RequiredArgsConstructor
 * public class OrderService {
 *     private final StateMachine<OrderStatus, Long> orderFlow;   // 字段名 = machine 名
 *
 *     @Transactional
 *     public void pay(Long orderId, String txnNo) {
 *         orderFlow.fire(orderId, "PAY", param("txnNo", txnNo), set("pay_no", txnNo));
 *         // 此处可继续其它业务写，同事务；任一步失败整体回滚
 *     }
 * }
 * }</pre>
 *
 * <p><b>状态唯一写入口铁律：</b>业务表的 status 列只能由本接口的 {@link #fire}
 * 通过 CAS 修改；守卫与动作里直接 UPDATE status、或业务代码绕过 fire 改状态，
 * 都会破坏并发正确性与流转记录的完整性，属于设计禁区。</p>
 *
 * <p>当存在多个同泛型状态机时，按字段名（= machine 名）区分注入；
 * 也可用 {@code @Qualifier("machine名")} 显式指定。</p>
 *
 * @param <S>  状态枚举类型
 * @param <ID> 实体主键类型
 */
public interface StateMachine<S, ID> {

    /**
     * Java DSL 入口：声明一个状态机定义（与 yml 通道等价，产出同一种
     * {@code MachineDefinition} 运行时模型），Bean 方法返回值交给框架装配：
     *
     * <pre>{@code
     * @Bean
     * StateMachineDefinition orderFlow() {
     *     return StateMachine.define("order", OrderStatus.class)
     *             .table("t_order", "status", "id")
     *             .idType(Long.class)
     *             .transition(OrderStatus.CREATED, OrderStatus.PAID, "PAY")
     *             .build();
     * }
     * }</pre>
     *
     * @param name      状态机名，即最终注册的 Bean 名，全局唯一
     * @param stateType 状态枚举类型
     * @param <S>       状态枚举类型
     * @return 定义构建器
     */
    static <S extends Enum<S>> DefinitionBuilder<S> define(String name, Class<S> stateType) {
        return new DefinitionBuilder<>(name, stateType);
    }

    /**
     * 触发流转：读当前态 → 路由查边 → 守卫 → CAS → 动作 → 发事件 →（记历史）。
     *
     * <p>事务语义：加入调用方已有事务；没有则自开。CAS 成功即持有行锁至事务提交，
     * 同事务内的后续业务字段更新天然并发安全。任一步失败整体回滚。</p>
     *
     * @param id     实体主键
     * @param event  事件名
     * @param args   {@link FireArg#param(String, Object)} / {@link FireArg#set(String, Object)} 令牌，可变参数
     * @throws io.github.biglv666.statekit.exception.IllegalTransitionException 当前状态没有 event 对应的出边，或实体不存在
     * @throws io.github.biglv666.statekit.exception.GuardRejectedException    守卫返回 false
     * @throws io.github.biglv666.statekit.exception.StateConflictException    CAS 未命中（conflict-strategy=throw 时）
     */
    void fire(ID id, String event, FireArg... args);

    /**
     * 带 {@link FireOptions} 的 fire 重载（0.2.0+）：
     * 如 {@code FireOptions.skipHistory()} 单次豁免历史记录。
     */
    default void fire(ID id, String event, FireOptions options, FireArg... args) {
        fire(id, event, args);
    }

    /**
     * 无冲突异常的 fire（0.2.0+）：语义与 {@link #fire(Object, String, FireArg...)}
     * 完全一致，唯独 CAS 未命中时不抛 {@code StateConflictException} 而是返回 false——
     * 调用方可感知冲突又不被迫 try-catch；非法流转与守卫拒绝仍照常抛出（确定性错误不该静默）。
     * 配置了自动重试时先重试，重试耗尽仍未命中才返回 false。
     *
     * @return true 流转成功；false CAS 冲突（含重试耗尽）
     */
    default boolean tryFire(ID id, String event, FireArg... args) {
        fire(id, event, args);
        return true;
    }

    /**
     * 查询实体当前状态。
     *
     * @param id 实体主键
     * @return 当前状态；实体不存在或状态列为 null 时为 empty
     */
    Optional<S> currentState(ID id);

    /**
     * 当前状态是否为终态（没有任何出边的状态）。
     *
     * @param id 实体主键
     * @return 实体不存在返回 false；存在且无出边返回 true
     * @throws io.github.biglv666.statekit.exception.EntityNotFoundException 实体不存在
     */
    boolean isFinal(ID id);

    /**
     * 当前状态的直接后继状态集合，供前端渲染「当前可执行的操作」。
     * 注意这是<b>状态图层面</b>的可达后继，未经过守卫校验，
     * 不代表每次 fire 都会成功（守卫可能拒绝、并发可能冲突）。
     *
     * @param id 实体主键
     * @return 后继状态集合，无出边时为空集合
     * @throws io.github.biglv666.statekit.exception.EntityNotFoundException 实体不存在
     */
    Set<S> nextStates(ID id);

    /**
     * 可操作视图（0.3.0+）：当前状态下可触发的事件结构化描述，
     * 供前端直接渲染操作按钮面板（event 名 + 描述 + 是否带守卫 + 期望的 param）。
     * 未经过守卫校验，仍可能 fire 失败。
     *
     * @param id 实体主键
     * @return 操作描述列表，无出边时为空列表
     * @throws io.github.biglv666.statekit.exception.EntityNotFoundException 实体不存在
     */
    java.util.List<ActionDescriptor> availableActions(ID id);
}
