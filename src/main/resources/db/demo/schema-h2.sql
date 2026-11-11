-- demo 模式建表脚本（H2 内存库，MySQL 兼容语法）
-- 结构与 db/migration 下 V1 + V2 迁移保持一致，仅去掉 ENGINE / CHARSET / COMMENT 等 MySQL 专有子句。
-- 正式部署仍由 Flyway 执行 db/migration 下的迁移脚本。
CREATE TABLE IF NOT EXISTS job_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    input_text TEXT,
    company_name VARCHAR(255),
    status VARCHAR(32),
    ai_verdict VARCHAR(255),
    interview_questions TEXT,
    created_at DATETIME,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS audit_signal (
    id BIGINT NOT NULL AUTO_INCREMENT,
    audit_id BIGINT NOT NULL,
    signal_type VARCHAR(64),
    severity VARCHAR(16),
    reason TEXT,
    confidence DECIMAL(3, 2),
    source VARCHAR(16),
    created_at DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_signal_job_audit FOREIGN KEY (audit_id) REFERENCES job_audit (id)
);
