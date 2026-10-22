package com.tasknest.common;

/**
 * 业务异常，携带 {@link ErrorCode} 作为响应契约。
 *
 * <p>{@link GlobalExceptionHandler} 会据此输出统一的 {@link ApiResponse} 与 HTTP 状态码；
 * {@code message} 可覆盖错误码的默认文案，用于补充上下文（如具体路径或字段）。</p>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 使用错误码的默认文案构造异常。
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    /**
     * 使用自定义文案构造异常，便于补充具体原因。
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
