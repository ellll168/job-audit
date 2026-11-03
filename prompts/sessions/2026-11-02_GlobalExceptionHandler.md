# 会话记录：全局异常处理（GlobalExceptionHandler）

- 项目：job-audit（岗位反向尽调助手）
- 会话日期：2026-11-03
- 涉及文件：
  - `src/main/java/com/example/jobaudit/domain/ErrorResponse.java`（新建）
  - `src/main/java/com/example/jobaudit/api/GlobalExceptionHandler.java`（新建）
  - `src/main/resources/application.properties`（修改）

---

## 用户提问

> @.trae-context.md 为了提升系统的鲁棒性，我需要加入全局异常处理。
> 要求：
>
> 在 com.example.jobaudit.api 包下创建一个全局异常处理类 GlobalExceptionHandler，使用 @RestControllerAdvice 注解。
>
> 拦截 MethodArgumentNotValidException（即 @Valid / @NotBlank 校验失败的异常）。当触发时，提取出具体的错误字段和错误提示（比如"inputText 不能为空"），返回 HTTP 状态码 400。
>
> 拦截通用的 Exception。如果发生未知错误，返回 HTTP 状态码 500，但绝对不能把 Java 的底层报错堆栈直接扔给前端，要包装成一个友好的提示，比如"服务器开小差了，请稍后再试"。
>
> 定义一个统一的错误返回实体（比如 ErrorResponse），包含 code（状态码）、message（提示语）、timestamp（时间戳）。

## 处理过程

1. 对齐 `.trae-context.md` 规范："异常统一走 @ControllerAdvice，不裸抛 500"。`ErrorResponse` 作为 DTO 放 domain 包，`GlobalExceptionHandler` 放 api 包。
2. `ErrorResponse` 用 record 实现，静态工厂 `of()` 自动填充 `LocalDateTime.now()` 时间戳。
3. 校验异常处理：拼接所有 `FieldError` 的 `getDefaultMessage()`（注解 message 本身已含字段名，不再重复拼接 field，避免 "inputText inputText 不能为空" 这种冗余）。
4. 兜底处理：`log.error("未处理异常", ex)` 把完整堆栈写进日志——由于异常处理与请求在同一线程，MDC 中的 traceId 会自动带入日志；前端只收到友好文案。
5. 顺手补齐遗留项：`application.properties` 增加 `logging.pattern.level=%5p [%X{traceId}]`，让上一轮埋进 MDC 的 traceId 真正输出到日志。

## 生成的完整代码

`src/main/java/com/example/jobaudit/domain/ErrorResponse.java`：

```java
package com.example.jobaudit.domain;

import java.time.LocalDateTime;

/**
 * 统一错误返回体。
 */
public record ErrorResponse(int code, String message, LocalDateTime timestamp) {

    public static ErrorResponse of(int code, String message) {
        return new ErrorResponse(code, message, LocalDateTime.now());
    }
}
```

`src/main/java/com/example/jobaudit/api/GlobalExceptionHandler.java`：

```java
package com.example.jobaudit.api;

import com.example.jobaudit.domain.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    /** 兜底：未知异常 → 500，堆栈只进日志（含 MDC traceId），前端只收友好提示 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnknown(Exception ex) {
        log.error("未处理异常", ex);
        return ErrorResponse.of(500, "服务器开小差了，请稍后再试");
    }
}
```

`application.properties` 追加：

```properties
logging.pattern.level=%5p [%X{traceId}]
```

## 验证过程与结果

真实启动应用（`mvn spring-boot:run`）后用 curl 验证三种场景：

| 场景 | 结果 |
|---|---|
| 两字段均为空 | `400 {"code":400,"message":"inputText 不能为空；companyName 不能为空","timestamp":...}` |
| 畸形 JSON（PowerShell 转义问题误触发） | `500 {"code":500,"message":"服务器开小差了，请稍后再试",...}`，无堆栈泄漏 |
| 正常请求 | `200 {"auditId":4,"status":"SCANNED","signals":[]}` 不受影响 |

**插曲**：首次用 curl 内联 `-d "{\"...\"}"` 发空字段请求时意外返回 500，排查后确认是 PowerShell 反斜杠转义导致 JSON 畸形（`HttpMessageNotReadableException`），恰好验证了兜底分支。改用 `--data-binary @file` 后 400 分支正常。

应用日志确认：

- `INFO [f73c67ef-...] 收到岗位审计请求` —— traceId 已输出，同一请求多条日志共享同一 traceId
- `WARN ... 参数校验失败：...` —— 400 分支日志正常
- `ERROR ... 未处理异常` + 完整堆栈 —— 堆栈只进日志

（控制台中文乱码为 Windows GBK 编码问题，不影响接口返回与文件日志。）

## 设计说明

- `@RestControllerAdvice` 默认对所有 Controller 生效，后续新增接口自动获得统一错误格式。
- 已知未覆盖：`HttpMessageNotReadableException`（JSON 畸形）目前走 500 兜底，语义上属客户端错误，后续可单独映射为 400。
