-- 测试库 schema（H2 内存模式，MySQL 兼容语法）
-- 生产迁移仍由 Flyway 的 db/migration 脚本负责；此处仅用于不依赖本机 MySQL 的自动化测试，
-- 结构与 V1 + V2 迁移保持一致（去掉 ENGINE/CHARSET/COMMENT 等 MySQL 专有子句）。
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
