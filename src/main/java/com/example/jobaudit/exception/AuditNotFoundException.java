package com.example.jobaudit.exception;

/**
 * 业务异常：审计记录不存在，由 GlobalExceptionHandler 映射为 404。
 */
public class AuditNotFoundException extends RuntimeException {

    public AuditNotFoundException(Long id) {
        super("审计记录不存在，id=" + id);
    }
}
