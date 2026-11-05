package com.example.jobaudit.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.jobaudit.domain.AuditRequest;
import com.example.jobaudit.domain.AuditResponse;
import com.example.jobaudit.domain.AuditResultResponse;
import com.example.jobaudit.domain.AuditSignal;
import com.example.jobaudit.domain.AuditSignalRecord;
import com.example.jobaudit.domain.AuditSignalView;
import com.example.jobaudit.domain.ExtractedSignal;
import com.example.jobaudit.domain.JobAudit;
import com.example.jobaudit.exception.AuditNotFoundException;
import com.example.jobaudit.mapper.AuditSignalRecordMapper;
import com.example.jobaudit.mapper.JobAuditMapper;
import com.example.jobaudit.rule.AuditRuleEngine;
import com.example.jobaudit.service.SignalExtractionService;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/audits")
@Validated
public class JobAuditController {

    private static final Logger log = LoggerFactory.getLogger(JobAuditController.class);

    private final JobAuditMapper jobAuditMapper;
    private final AuditSignalRecordMapper signalRecordMapper;
    private final AuditRuleEngine ruleEngine;
    private final SignalExtractionService signalExtractionService;

    public JobAuditController(JobAuditMapper jobAuditMapper,
                              AuditSignalRecordMapper signalRecordMapper,
                              AuditRuleEngine ruleEngine,
                              SignalExtractionService signalExtractionService) {
        this.jobAuditMapper = jobAuditMapper;
        this.signalRecordMapper = signalRecordMapper;
        this.ruleEngine = ruleEngine;
        this.signalExtractionService = signalExtractionService;
    }

    @PostMapping
    public AuditResponse create(@Validated @RequestBody AuditRequest request) {
        log.info("收到岗位审计请求，traceId={}，companyName={}", MDC.get(TraceIdFilter.TRACE_ID), request.companyName());

        JobAudit jobAudit = new JobAudit();
        jobAudit.setCompanyName(request.companyName());
        jobAudit.setInputText(request.inputText());
        jobAudit.setStatus("SCANNED");
        jobAudit.setCreatedAt(LocalDateTime.now());
        jobAuditMapper.insert(jobAudit);

        List<AuditSignal> ruleSignals = ruleEngine.analyze(request.inputText());
        List<AuditSignalView> views = new ArrayList<>(ruleSignals.stream()
                .map(AuditSignalView::fromRule)
                .toList());
        List<AuditSignalRecord> records = ruleSignals.stream()
                .map(s -> toRecord(s, jobAudit.getId()))
                .toList();
        records.forEach(signalRecordMapper::insert);
        log.info("规则扫描完成，auditId={}，命中信号数={}，已全部落库", jobAudit.getId(), ruleSignals.size());

        // LLM 语义扫描：extract() 内部已全量兜底，失败只会降级 pending_review，不会影响规则结果
        ExtractedSignal semantic = signalExtractionService.extract(request.inputText());
        if ("OK".equals(semantic.status()) && !"none".equals(semantic.signalType())) {
            String severity = severityOf(semantic.confidence());
            views.add(AuditSignalView.fromLlm(semantic, severity));
            signalRecordMapper.insert(toSemanticRecord(semantic, jobAudit.getId(), severity));
            log.info("LLM 语义信号命中，auditId={}，signalType={}，confidence={}，已落库",
                    jobAudit.getId(), semantic.signalType(), semantic.confidence());
        } else {
            log.info("LLM 未产生语义信号，auditId={}，status={}", jobAudit.getId(), semantic.status());
        }

        return new AuditResponse(jobAudit.getId(), jobAudit.getStatus(), views);
    }

    @GetMapping("/{id}")
    public AuditResultResponse getById(@PathVariable Long id) {
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
                signals);
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

    /** LLM 语义信号 -> audit_signal 表实体；source 固定为 LLM，保留模型给出的 confidence */
    private AuditSignalRecord toSemanticRecord(ExtractedSignal signal, Long auditId, String severity) {
        AuditSignalRecord record = new AuditSignalRecord();
        record.setAuditId(auditId);
        record.setSignalType(signal.signalType());
        record.setSeverity(severity);
        record.setReason(signal.reason());
        record.setConfidence(BigDecimal.valueOf(signal.confidence()));
        record.setSource("LLM");
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    /** LLM 不给 severity，按置信度映射：>=0.8 高危，>=0.5 中危，其余低危 */
    private String severityOf(Double confidence) {
        if (confidence >= 0.8) {
            return "HIGH";
        }
        if (confidence >= 0.5) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
