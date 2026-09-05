package io.github.biglv666.statekit.testapp;

/**
 * 任务状态枚举（测试用，对应 machine=task，conflict-strategy=log）。
 */
public enum TaskStatus {
    NEW, RUNNING, FINISHED, ABORTED
}
