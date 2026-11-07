package com.example.jobaudit.service;

import com.example.jobaudit.domain.AuditSignalRecord;
import com.example.jobaudit.domain.JobAudit;
import com.example.jobaudit.mapper.JobAuditMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计结果持久化：只负责数据库写入，保证 job_audit 与 audit_signal 在同一短事务内原子完成。
 */
@Service
public class AuditPersistenceService {

    /** 批量插入分片大小 */
    private static final int BATCH_SIZE = 50;

    private static final String STATUS_SCANNED = "SCANNED";

    private final JobAuditMapper jobAuditMapper;
    private final AuditSignalRecordService signalRecordService;

    public AuditPersistenceService(JobAuditMapper jobAuditMapper,
                                   AuditSignalRecordService signalRecordService) {
        this.jobAuditMapper = jobAuditMapper;
        this.signalRecordService = signalRecordService;
    }

    /**
     * 主记录入库并回填自增主键，随后批量写入信号。
     * 任一写入失败，job_audit 与 audit_signal 全部回滚。
     */
    @Transactional
    public JobAudit saveAudit(String companyName, String jdText, List<AuditSignalRecord> signals) {
        JobAudit jobAudit = new JobAudit();
        jobAudit.setCompanyName(companyName);
        jobAudit.setInputText(jdText);
        jobAudit.setStatus(STATUS_SCANNED);
        jobAudit.setCreatedAt(LocalDateTime.now());
        jobAuditMapper.insert(jobAudit);

        Long auditId = jobAudit.getId();
        LocalDateTime signalCreatedAt = LocalDateTime.now();
        for (AuditSignalRecord signal : signals) {
            signal.setAuditId(auditId);
            signal.setCreatedAt(signalCreatedAt);
        }
        if (!signals.isEmpty()) {
            signalRecordService.saveBatch(signals, BATCH_SIZE);
        }
        return jobAudit;
    }
}
