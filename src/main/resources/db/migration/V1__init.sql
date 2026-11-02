CREATE TABLE job_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    input_text TEXT,
    company_name VARCHAR(255),
    status VARCHAR(32),
    created_at DATETIME,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE audit_signal (
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
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
