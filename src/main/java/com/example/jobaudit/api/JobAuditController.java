package com.example.jobaudit.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.jobaudit.domain.AuditRequest;
import com.example.jobaudit.domain.AuditResultResponse;
import com.example.jobaudit.domain.AuditSignalRecord;
import com.example.jobaudit.domain.InterviewQuestionsResponse;
import com.example.jobaudit.domain.JobAudit;
import com.example.jobaudit.exception.AuditNotFoundException;
import com.example.jobaudit.mapper.AuditSignalRecordMapper;
import com.example.jobaudit.mapper.JobAuditMapper;
import com.example.jobaudit.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audits")
@Validated
public class JobAuditController {

    private static final Logger log = LoggerFactory.getLogger(JobAuditController.class);

    private final AuditService auditService;
    private final JobAuditMapper jobAuditMapper;
    private final AuditSignalRecordMapper signalRecordMapper;

    public JobAuditController(AuditService auditService,
                              JobAuditMapper jobAuditMapper,
                              AuditSignalRecordMapper signalRecordMapper) {
        this.auditService = auditService;
        this.jobAuditMapper = jobAuditMapper;
        this.signalRecordMapper = signalRecordMapper;
    }

    @PostMapping
    public AuditResultResponse create(@Validated @RequestBody AuditRequest request) {
        log.info("收到岗位审计请求，traceId={}，companyName={}",
                MDC.get(TraceIdFilter.TRACE_ID), request.companyName());

        AuditResultResponse response = auditService.processAndSaveAudit(request.companyName(), request.inputText());

        log.info("审计处理完成，auditId={}，返回信号数={}", response.auditId(), response.signals().size());
        return response;
    }

    @GetMapping("/{id}")
    public AuditResultResponse getById(@PathVariable("id") Long id) {
        log.info("查询审计记录，traceId={}，auditId={}", MDC.get(TraceIdFilter.TRACE_ID), id);

        JobAudit jobAudit = jobAuditMapper.selectById(id);
        if (jobAudit == null) {
            throw new AuditNotFoundException(id);
        }

        List<AuditSignalRecord> signals = signalRecordMapper.selectList(
                new QueryWrapper<AuditSignalRecord>().eq("audit_id", id).orderByAsc("id"));
        log.info("查询完成，auditId={}，关联信号数={}", id, signals.size());

        return new AuditResultResponse(
                jobAudit.getId(),
                jobAudit.getCompanyName(),
                jobAudit.getInputText(),
                jobAudit.getStatus(),
                jobAudit.getCreatedAt(),
                signals,
                jobAudit.getAiVerdict());
    }

    @GetMapping("/{id}/questions")
    public InterviewQuestionsResponse getInterviewQuestions(@PathVariable("id") Long id) {
        log.info("生成面试反问话术，traceId={}，auditId={}", MDC.get(TraceIdFilter.TRACE_ID), id);

        InterviewQuestionsResponse response = auditService.generateInterviewQuestions(id);

        log.info("反问话术生成完成，auditId={}，问题数={}", id, response.questions().size());
        return response;
    }
}
