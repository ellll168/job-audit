package com.example.jobaudit.api;

import com.example.jobaudit.domain.ErrorResponse;
import com.example.jobaudit.exception.AuditNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：统一错误响应格式，禁止把 Java 堆栈直接抛给前端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数校验失败（@Validated / @NotBlank 等）→ 400，返回具体字段的提示 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败：{}", message);
        return ErrorResponse.of(400, message);
    }

    /** 业务异常：审计记录不存在 → 404 */
    @ExceptionHandler(AuditNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(AuditNotFoundException ex) {
        log.warn("查询失败：{}", ex.getMessage());
        return ErrorResponse.of(404, ex.getMessage());
    }

    /** 路径不存在 → 404：避免被兜底处理器吞成 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNoResource(NoResourceFoundException ex) {
        return ErrorResponse.of(404, "接口不存在：" + ex.getResourcePath());
    }

    /** 路径存在但不支持该 HTTP 方法 → 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ErrorResponse handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("请求方法不支持：{}", ex.getMessage());
        return ErrorResponse.of(405, "请求方法不支持：" + ex.getMethod());
    }

    /** 兜底：未知异常 → 500，堆栈只进日志（含 MDC traceId），前端只收友好提示 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnknown(Exception ex) {
        log.error("未处理异常", ex);
        return ErrorResponse.of(500, "服务器开小差了，请稍后再试");
    }
}
