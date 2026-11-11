ALTER TABLE job_audit
    ADD COLUMN ai_verdict VARCHAR(255) NULL COMMENT 'AI 一句话毒舌锐评',
    ADD COLUMN interview_questions TEXT NULL COMMENT '面试反问话术，换行分隔';
