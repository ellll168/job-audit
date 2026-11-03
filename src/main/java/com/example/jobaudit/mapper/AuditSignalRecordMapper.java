package com.example.jobaudit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.jobaudit.domain.AuditSignalRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditSignalRecordMapper extends BaseMapper<AuditSignalRecord> {
}
