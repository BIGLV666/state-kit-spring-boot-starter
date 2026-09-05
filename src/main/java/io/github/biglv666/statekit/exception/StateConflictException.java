package io.github.biglv666.statekit.exception;

import io.github.biglv666.webcommon.annotation.DefaultErrorCode;
import io.github.biglv666.webcommon.result.ResultCode;

/**
 * 状态冲突：CAS 更新未命中（{@code WHERE id=? AND status=from} 影响行数为 0），
 * 即数据库实际状态与期望的 from 不一致。
 *
 * <p>典型来源：并发双流转（数据库行锁保证恰好一方 CAS 命中，另一方收到本异常）、
 * 状态已被其它路径修改。异常中携带<b>期望态</b>与<b>重读后的实际态</b>，
 * 调用方可据此决定提示用户刷新还是走补偿逻辑。</p>
 *
 * <p>重试语义：与 {@link IllegalTransitionException} 不同，本异常是竞态窗口问题，
 * 重读状态后重试可能成功（V1.5 提供自动重试策略）。</p>
 */
@DefaultErrorCode(value = ResultCode.class, constant = "CONFLICT")
public class StateConflictException extends StateKitException {

    /** 状态机名 */
    private final transient String machine;
    /** 期望的 from 状态 */
    private final transient String expected;
    /** CAS 失败后重读的实际状态；实体已不存在时为 null */
    private final transient String actual;

    public StateConflictException(String machine, Object entityId, String expected, String actual) {
        super("状态冲突: 状态机 [%s] 实体 [%s] 期望状态 [%s], 实际状态 [%s]%s"
                .formatted(machine, entityId, expected, actual,
                        actual == null ? "（实体可能已被删除）" : "，请刷新后重试或走补偿逻辑"));
        this.machine = machine;
        this.expected = expected;
        this.actual = actual;
    }

    /** 状态机名 */
    public String getMachine() {
        return machine;
    }

    /** 期望的 from 状态名 */
    public String getExpected() {
        return expected;
    }

    /** CAS 失败后重读的实际状态名，实体已不存在时为 null */
    public String getActual() {
        return actual;
    }
}
