package com.tasknest.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;

/**
 * 全局异常处理，把所有 REST 异常转换为统一的 {@link ApiResponse}。
 *
 * <p>{@link BusinessException} 按携带的 {@link ErrorCode} 输出业务码与 HTTP 状态；
 * Bean Validation、JSON 解析、参数类型 / 缺失等请求错误统一映射为
 * {@code INVALID_PARAM (40001)}；SSE 客户端中途断开属于正常现象，只记 debug 日志、
 * 不再写响应；其余未捕获异常记录 error 日志并返回 {@code INTERNAL (50001)}。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理显式抛出的业务异常，保留错误码与自定义文案。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        return build(exception.getErrorCode(), exception.getMessage());
    }

    /**
     * 处理请求体验证失败，取第一条字段错误拼成 {@code 字段 描述} 文案。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse(ErrorCode.INVALID_PARAM.defaultMessage());
        return build(ErrorCode.INVALID_PARAM, message);
    }

    /**
     * 处理请求体无法解析、参数类型不匹配或缺少必填查询参数，统一按参数错误返回。
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception exception) {
        return build(ErrorCode.INVALID_PARAM, ErrorCode.INVALID_PARAM.defaultMessage());
    }

    /**
     * 客户端在 SSE 写入过程中断开是正常现象，不是服务端故障。
     *
     * <p>此时响应已提交，不能再写回 {@link ApiResponse}，因此只记录 debug 日志。</p>
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, IOException.class})
    public void handleClientDisconnect(IOException exception) {
        log.debug("SSE client disconnected: {}", exception.getMessage());
    }

    /**
     * 兜底处理未预期异常：记录完整堆栈，对外只暴露通用内部错误。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Unhandled server error", exception);
        return build(ErrorCode.INTERNAL, ErrorCode.INTERNAL.defaultMessage());
    }

    /**
     * 按错误码构造 JSON 响应，HTTP 状态与业务码均取自 {@link ErrorCode}。
     */
    private ResponseEntity<ApiResponse<Void>> build(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(errorCode.code(), message));
    }
}
