package io.github.biglv666.statekit.testapp;

/**
 * 流程状态枚举（测试用，对应 machine=flow：同一 event 从不同状态到不同目标 + String 主键）。
 */
public enum FlowStatus {
    A, B, C, D
}
