package io.github.biglv666.statekit.testapp;

import io.github.biglv666.statekit.StateAction;
import io.github.biglv666.statekit.StateGuard;
import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.StateTx;
import io.github.biglv666.statekit.define.MachineDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用钩子与 Java DSL 状态机声明。
 */
@Configuration
public class TestHooksConfig {

    /** Java DSL 通道声明：与 yml 通道并存，验证双通道合流 */
    @Bean
    public MachineDefinition dslOrder() {
        return StateMachine.define("dslOrder", OrderStatus.class)
                .table("t_order2", "status", "id")
                .idType(Long.class)
                .transition(OrderStatus.CREATED, OrderStatus.PAID, "PAY")
                .transition(OrderStatus.PAID, OrderStatus.SHIPPED, "SHIP")
                .build();
    }

    /** 签收守卫：param 携带 reject=true 时拒绝 */
    @Bean
    public StateGuard<OrderStatus, Long> orderSignGuard() {
        return tx -> !Boolean.TRUE.equals(tx.param("reject", Boolean.class));
    }

    /** 签收动作：写 sign_time 列，并记录收到的 tx 供断言 */
    @Bean
    public StateAction<OrderStatus, Long> orderSignAction(JdbcTemplate jdbcTemplate) {
        return tx -> {
            jdbcTemplate.update("UPDATE t_order SET sign_time = ? WHERE id = ?",
                    LocalDateTime.now(), tx.entityId());
            LastTx.record("orderSignAction", tx);
        };
    }

    /** 发货动作：写 remark 列 */
    @Bean
    public StateAction<OrderStatus, Long> orderShipAction(JdbcTemplate jdbcTemplate) {
        return tx -> jdbcTemplate.update("UPDATE t_order SET remark = ? WHERE id = ?", "shipped", tx.entityId());
    }

    /** 取消守卫：param 携带 reject=true 时拒绝 */
    @Bean
    public StateGuard<OrderStatus, Long> cancelGuard() {
        return tx -> !Boolean.TRUE.equals(tx.param("reject", Boolean.class));
    }

    /** 超时取消动作：写 note 列，供扫描器测试断言动作确实执行 */
    @Bean
    public StateAction<OrderStatus, Long> timerCancelAction(JdbcTemplate jdbcTemplate) {
        return tx -> jdbcTemplate.update("UPDATE t_timer_order SET note = ? WHERE id = ?",
                "timer-cancelled", tx.entityId());
    }

    /** 超时守卫开关：gate 关闭时拒绝（验证守卫拒绝 → 下轮重试语义） */
    @Bean
    public StateGuard<io.github.biglv666.statekit.testapp.FlowStatus, Long> timerGateGuard() {
        return tx -> !GateHolder.closed.get();
    }

    /** 超时守卫的用例开关 */
    public static final class GateHolder {
        public static final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
    }

    /** 自爆动作：先写 note 列再抛异常，验证整体回滚 */
    @Bean
    public StateAction<TaskStatus, Long> boomAction(JdbcTemplate jdbcTemplate) {
        return tx -> {
            jdbcTemplate.update("UPDATE t_task SET note = ? WHERE id = ?", "boom", tx.entityId());
            throw new IllegalStateException("boom from action");
        };
    }

    /** 0.3.0 补偿策略（compFlow 机器）：决策存于 ModeHolder 供各用例切换 */
    @Bean
    public io.github.biglv666.statekit.compensation.CompensationPolicy compFlowPolicy() {
        return ctx -> switch (CompModeHolder.mode) {
            case RETRY -> io.github.biglv666.statekit.compensation.CompensationDecision.retryWith("GO");
            case SCHEDULE -> io.github.biglv666.statekit.compensation.CompensationDecision.schedule("GO", 30_000);
            case ABORT -> io.github.biglv666.statekit.compensation.CompensationDecision.abort();
        };
    }

    /** 补偿策略的用例切换开关 */
    public static final class CompModeHolder {
        public enum Mode { RETRY, SCHEDULE, ABORT }

        public static volatile Mode mode = Mode.ABORT;
    }

    /** 记录最近一次动作收到的 tx 快照（测试断言用） */
    public static final class LastTx {
        public static final List<String> seen = new ArrayList<>();
        public static volatile Object entityId;
        public static volatile String event;
        public static volatile Object from;
        public static volatile Object to;
        public static final AtomicInteger count = new AtomicInteger();

        static void record(String hook, StateTx<?, ?> tx) {
            seen.add(hook);
            entityId = tx.entityId();
            event = tx.event();
            from = tx.from();
            to = tx.to();
            count.incrementAndGet();
        }

        public static void reset() {
            seen.clear();
            entityId = null;
            event = null;
            from = null;
            to = null;
            count.set(0);
        }
    }
}
