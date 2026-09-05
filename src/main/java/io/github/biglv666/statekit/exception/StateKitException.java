package io.github.biglv666.statekit.exception;

/**
 * state-kit 异常体系基类。
 *
 * <p>所有异常均为 RuntimeException，向调用方传播时由 web-common 的全局异常处理器
 * 按各类上的 {@code @DefaultErrorCode} 注解映射为统一 Result
 * （web-common 不在类路径时无映射，异常原样抛出，由应用自行处理）。</p>
 */
public class StateKitException extends RuntimeException {

    public StateKitException(String message) {
        super(message);
    }

    public StateKitException(String message, Throwable cause) {
        super(message, cause);
    }
}
