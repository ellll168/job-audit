package com.example.jobaudit.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.jobaudit.domain.AuditSignalRecord;
import com.example.jobaudit.mapper.AuditSignalRecordMapper;
import org.springframework.stereotype.Service;

/**
 * audit_signal 的批量写入入口：基于 MyBatis-Plus ServiceImpl 提供 saveBatch。
 */
@Service
public class AuditSignalRecordService extends ServiceImpl<AuditSignalRecordMapper, AuditSignalRecord> {
}
