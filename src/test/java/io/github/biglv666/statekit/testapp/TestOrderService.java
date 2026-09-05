package io.github.biglv666.statekit.testapp;

import io.github.biglv666.statekit.StateMachine;
import io.github.biglv666.statekit.exception.StateConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试用业务服务：验证 fire 加入外部事务的边界（提交 / 回滚）。
 */
@Service
public class TestOrderService {

    private final StateMachine<OrderStatus, Long> order;

    /** 注入演示：同泛型多状态机时按 bean 名（machine 名）+ @Qualifier 定位 */
    public TestOrderService(@org.springframework.beans.factory.annotation.Qualifier("order")
                            StateMachine<OrderStatus, Long> order) {
        this.order = order;
    }

    /** 外部事务 + 正常提交 */
    @Transactional
    public void payInTx(Long orderId, String txnNo) {
        order.fire(orderId, "PAY", io.github.biglv666.statekit.FireArg.set("pay_no", txnNo));
    }

    /** 外部事务 + 后续步骤失败 → 整体回滚 */
    @Transactional
    public void payThenFail(Long orderId) {
        order.fire(orderId, "PAY", io.github.biglv666.statekit.FireArg.set("pay_no", "will-rollback"));
        throw new IllegalStateException("业务后续步骤失败");
    }

    /** 外部事务内 fire 非法流转：异常穿透，事务回滚标记 */
    @Transactional
    public void illegalFireInTx(Long orderId) {
        order.fire(orderId, "SIGN");
    }

    /** 预置冲突：先把状态改成 PAID，再以 CREATED 期望触发 PAY */
    @Transactional
    public void fireExpectingConflict(Long orderId) throws StateConflictException {
        order.fire(orderId, "PAY");
    }

    /** 无事务方法：fire 自开事务 */
    public void payWithoutTx(Long orderId, String txnNo) {
        order.fire(orderId, "PAY", io.github.biglv666.statekit.FireArg.set("pay_no", txnNo));
    }
}
