package io.github.biglv666.statekit;

/**
 * 可操作视图条目（0.3.0+）：前端按此渲染操作面板，不再硬编码状态判断。
 *
 * @param event         事件名
 * @param to            目标状态
 * @param description   声明的可选描述（yml/DSL 的 description 字段）
 * @param isGuarded     该边是否挂守卫（前端据此提示可能拒绝）
 * @param requiredParams 声明的期望 param 键名（yml/DSL 的 params 字段），供前端表单提示
 */
public record ActionDescriptor(String event, String to, String description,
                               boolean isGuarded, java.util.Set<String> requiredParams) {
}
