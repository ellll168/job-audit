package com.example.jobaudit.api;

import com.example.jobaudit.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 开发期运维接口，只在 dev profile 下注册，默认不对外暴露。
 * 该接口会批量调用大模型，无鉴权，绝不能出现在生产环境。
 * 启用方式：--spring.profiles.active=dev
 */
@RestController
@RequestMapping("/api/audits")
@Profile("dev")
public class DevAdminController {

    private static final Logger log = LoggerFactory.getLogger(DevAdminController.class);

    private final AuditService auditService;

    public DevAdminController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** 一次性管理操作：回填历史记录中 ai_verdict 为空的记录 */
    @PostMapping("/backfill-verdicts")
    public Map<String, Integer> backfillVerdicts() {
        log.info("触发 AI 锐评回填（dev only），traceId={}", MDC.get(TraceIdFilter.TRACE_ID));
        return auditService.backfillAiVerdicts();
    }
}
