package io.github.biglv666.statekit.exception;

import io.github.biglv666.webcommon.annotation.DefaultErrorCode;
import io.github.biglv666.webcommon.result.ResultCode;

/**
 * 实体不存在：查询当前状态或触发流转时，按 id 未找到业务表记录。
 */
@DefaultErrorCode(value = ResultCode.class, constant = "NOT_FOUND")
public class EntityNotFoundException extends StateKitException {

    /** 状态机名 */
    private final transient String machine;

    public EntityNotFoundException(String machine, Object entityId) {
        super("实体不存在: 状态机 [%s] id [%s]".formatted(machine, entityId));
        this.machine = machine;
    }

    /** 状态机名 */
    public String getMachine() {
        return machine;
    }
}
