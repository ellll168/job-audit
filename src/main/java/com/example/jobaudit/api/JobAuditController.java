package com.example.jobaudit.api;

import com.example.jobaudit.domain.AuditRequest;
import com.example.jobaudit.domain.AuditResponse;
import com.example.jobaudit.domain.AuditSignal;
import com.example.jobaudit.domain.AuditSignalRecord;
import com.example.jobaudit.domain.JobAudit;
import com.example.jobaudit.mapper.AuditSignalRecordMapper;
import com.example.jobaudit.mapper.JobAuditMapper;
import com.example.jobaudit.rule.AuditRuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/audits")
@Validated
public class JobAuditController {

    private static final Logger log = LoggerFactory.getLogger(JobAuditController.class);

    private final JobAuditMapper jobAuditMapper;
    private final AuditSignalRecordMapper signalRecordMapper;
    private final AuditRuleEngine ruleEngine;

    public JobAuditController(JobAuditMapper jobAuditMapper,
                              AuditSignalRecordMapper signalRecordMapper,
                              AuditRuleEngine ruleEngine) {
        this.jobAuditMapper = jobAuditMapper;
        this.signalRecordMapper = signalRecordMapper;
        this.ruleEngine = ruleEngine;
    }

    @PostMapping
    public AuditResponse create(@Validated @RequestBody AuditRequest request) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        try {
            log.info("收到岗位审计请求，companyName={}", request.companyName());

            JobAudit jobAudit = new JobAudit();
            jobAudit.setCompanyName(request.companyName());
            jobAudit.setInputText(request.inputText());
            jobAudit.setStatus("SCANNED");
            jobAudit.setCreatedAt(LocalDateTime.now());
            jobAuditMapper.insert(jobAudit);

            List<AuditSignal> signals = ruleEngine.analyze(request.inputText());

            List<AuditSignalRecord> records = signals.stream()
                    .map(s -> toRecord(s, jobAudit.getId()))
                    .toList();
            records.forEach(signalRecordMapper::insert);
            log.info("规则扫描完成，auditId={}，命中信号数={}，已全部落库", jobAudit.getId(), records.size());

            return new AuditResponse(jobAudit.getId(), jobAudit.getStatus(), signals);
        } finally {
            MDC.remove("traceId");
        }
    }

    /** 规则引擎输出 -> audit_signal 表实体；规则信号无置信度评分，source 固定为 RULE */
    private AuditSignalRecord toRecord(AuditSignal signal, Long auditId) {
        AuditSignalRecord record = new AuditSignalRecord();
        record.setAuditId(auditId);
        record.setSignalType(signal.signalType());
        record.setSeverity(signal.severity());
        record.setReason(signal.reason());
        record.setSource("RULE");
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }
}
