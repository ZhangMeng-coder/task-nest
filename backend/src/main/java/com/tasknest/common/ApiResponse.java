package com.tasknest.common;

/**
 * 所有 REST 接口（SSE 除外）的统一响应信封。
 *
 * <p>{@code code} 为业务码：{@code 0} 表示成功，非 0 取自 {@link ErrorCode}；
 * {@code message} 为可展示文案；{@code data} 为业务数据，失败时为 {@code null}。
 * 失败响应的 HTTP 状态码由 {@link GlobalExceptionHandler} 按 {@link ErrorCode} 映射。</p>
 *
 * @param <T> 业务数据类型
 */
public record ApiResponse<T>(int code, String message, T data) {

    /**
     * 构造成功响应（{@code code=0}，{@code message="ok"}）。
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    /**
     * 构造失败响应，{@code data} 固定为 {@code null}。
     *
     * @param code    非 0 业务错误码，通常来自 {@link ErrorCode#code()}
     * @param message 可展示的错误文案
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
