package com.example.jobaudit.service;

import com.example.jobaudit.domain.AuditResultResponse;
import com.example.jobaudit.domain.AuditSignal;
import com.example.jobaudit.domain.AuditSignalRecord;
import com.example.jobaudit.domain.ExtractedSignal;
import com.example.jobaudit.domain.JobAudit;
import com.example.jobaudit.rule.AuditRuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 审计业务编排：规则引擎 + LLM 语义提取 → 合并去重 → 落库 → 返回结果。
 * 规则计算与 LLM 调用都在事务之外完成，只有数据库写入进入短事务，
 * 避免数秒级的模型调用长时间占用数据库连接与事务。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private static final String SOURCE_RULE = "RULE";
    private static final String SOURCE_LLM = "LLM";
    private static final String NO_SIGNAL = "none";

    /** 模型未给 confidence 时的兜底值 */
    private static final double DEFAULT_LLM_CONFIDENCE = 0.8;

    private final AuditPersistenceService auditPersistenceService;
    private final AuditRuleEngine ruleEngine;
    private final SignalExtractionService signalExtractionService;

    public AuditService(AuditPersistenceService auditPersistenceService,
                        AuditRuleEngine ruleEngine,
                        SignalExtractionService signalExtractionService) {
        this.auditPersistenceService = auditPersistenceService;
        this.ruleEngine = ruleEngine;
        this.signalExtractionService = signalExtractionService;
    }

    /**
     * 处理一次岗位审计。
     * 事务外：规则扫描、LLM 语义提取、合并去重（纯计算，不持有数据库连接）；
     * 事务内：job_audit 插入 + audit_signal 批量写入，任一失败整体回滚。
     */
    public AuditResultResponse processAndSaveAudit(String companyName, String jdText) {
        List<AuditSignalRecord> ruleRecords = ruleEngine.analyze(jdText).stream()
                .map(this::toRuleRecord)
                .toList();
        List<AuditSignalRecord> llmRecords = toLlmRecords(signalExtractionService.extract(jdText));

        List<AuditSignalRecord> merged = mergeAndDeduplicate(ruleRecords, llmRecords);

        JobAudit jobAudit = auditPersistenceService.saveAudit(companyName, jdText, merged);

        log.info("审计完成，auditId={}，规则信号={}，LLM信号={}，合并去重后={}，已批量落库",
                jobAudit.getId(), ruleRecords.size(), llmRecords.size(), merged.size());

        return new AuditResultResponse(jobAudit.getId(), companyName, jdText,
                jobAudit.getStatus(), jobAudit.getCreatedAt(), merged);
    }

    /** 规则引擎输出 -> audit_signal 实体；auditId 与 createdAt 由持久化层统一回填 */
    private AuditSignalRecord toRuleRecord(AuditSignal signal) {
        AuditSignalRecord record = new AuditSignalRecord();
        record.setSignalType(signal.signalType());
        record.setSeverity(signal.severity());
        record.setReason(signal.reason());
        record.setSource(SOURCE_RULE);
        return record;
    }

    /**
     * LLM 输出 -> audit_signal 实体。
     * 未产生有效语义信号（none / 解析失败 pending_review）时返回空列表，不影响规则结果。
     */
    private List<AuditSignalRecord> toLlmRecords(ExtractedSignal signal) {
        if (!"OK".equals(signal.status())
                || signal.signalType() == null
                || NO_SIGNAL.equals(signal.signalType())) {
            log.info("LLM 未产生语义信号，status={}，signalType={}", signal.status(), signal.signalType());
            return List.of();
        }
        double confidence = signal.confidence() == null ? DEFAULT_LLM_CONFIDENCE : signal.confidence();
        AuditSignalRecord record = new AuditSignalRecord();
        record.setSignalType(signal.signalType());
        record.setSeverity(severityOf(confidence));
        record.setReason(signal.reason());
        record.setConfidence(BigDecimal.valueOf(confidence));
        record.setSource(SOURCE_LLM);
        return List.of(record);
    }

    /**
     * 合并规则信号与 LLM 信号，按 signalType 去重：
     * 1) 同一 signalType 同时被 RULE 和 LLM 命中时，保留 RULE（规则判定确定，不依赖模型输出）；
     * 2) 同类信号（同为 LLM）保留 confidence 更高者；规则信号无 confidence，比较时按 0 处理。
     */
    private List<AuditSignalRecord> mergeAndDeduplicate(List<AuditSignalRecord> ruleRecords,
                                                        List<AuditSignalRecord> llmRecords) {
        return Stream.concat(ruleRecords.stream(), llmRecords.stream())
                .filter(record -> record.getSignalType() != null)
                .collect(Collectors.toMap(
                        AuditSignalRecord::getSignalType,
                        Function.identity(),
                        this::preferSignal,
                        LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    /** 冲突裁决：规则优先于 LLM；同来源时保留置信度更高的一个 */
    private AuditSignalRecord preferSignal(AuditSignalRecord existing, AuditSignalRecord candidate) {
        boolean existingIsRule = SOURCE_RULE.equals(existing.getSource());
        boolean candidateIsRule = SOURCE_RULE.equals(candidate.getSource());
        if (existingIsRule != candidateIsRule) {
            return existingIsRule ? existing : candidate;
        }
        return confidenceOf(candidate).compareTo(confidenceOf(existing)) > 0 ? candidate : existing;
    }

    private BigDecimal confidenceOf(AuditSignalRecord record) {
        return record.getConfidence() == null ? BigDecimal.ZERO : record.getConfidence();
    }

    /** LLM 不给 severity，按置信度映射：>=0.8 高危，>=0.5 中危，其余低危 */
    private String severityOf(double confidence) {
        if (confidence >= 0.8) {
            return "HIGH";
        }
        if (confidence >= 0.5) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
