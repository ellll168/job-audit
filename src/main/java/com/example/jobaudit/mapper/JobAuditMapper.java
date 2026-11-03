package com.example.jobaudit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.jobaudit.domain.JobAudit;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JobAuditMapper extends BaseMapper<JobAudit> {
}
