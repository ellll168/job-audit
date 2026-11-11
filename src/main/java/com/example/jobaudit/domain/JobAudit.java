package com.example.jobaudit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * job_audit 表实体：一次岗位审计的输入记录。
 * MyBatis-Plus 默认开启驼峰转下划线，字段自动映射 input_text / company_name / created_at。
 */
@TableName("job_audit")
public class JobAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String inputText;

    private String companyName;

    private String status;

    private LocalDateTime createdAt;

    private String aiVerdict;

    /** 面试反问话术，换行分隔，懒生成后回写缓存 */
    private String interviewQuestions;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getInputText() {
        return inputText;
    }

    public void setInputText(String inputText) {
        this.inputText = inputText;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getAiVerdict() {
        return aiVerdict;
    }

    public void setAiVerdict(String aiVerdict) {
        this.aiVerdict = aiVerdict;
    }

    public String getInterviewQuestions() {
        return interviewQuestions;
    }

    public void setInterviewQuestions(String interviewQuestions) {
        this.interviewQuestions = interviewQuestions;
    }
}
