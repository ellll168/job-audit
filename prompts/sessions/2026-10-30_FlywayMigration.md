# 会话记录：Flyway 初始化迁移脚本

- 项目：job-audit（岗位反向尽调助手）
- 会话日期：2026-11-02
- 涉及文件：`src/main/resources/db/migration/V1__init.sql`

---

## 用户提问

> @.trae-context.md 帮我写一个 Flyway 的迁移脚本。
> 要求：
>
> 文件名为 V1__init.sql，只输出 SQL 脚本内容，不要 Java 代码。
>
> 包含两张表：
>
> job_audit：包含 id (BIGINT 主键自增), input_text (TEXT), company_name (VARCHAR 255), status (VARCHAR 32), created_at (DATETIME)。
>
> audit_signal：包含 id (BIGINT 主键自增), audit_id (BIGINT, 关联 job_audit), signal_type (VARCHAR 64), severity (VARCHAR 16), reason (TEXT), confidence (DECIMAL(3,2)), source (VARCHAR 16)，以及 created_at (DATETIME)。
>
> 请使用 InnoDB 引擎，字符集设置为 utf8mb4。
>
> 不要使用 Hibernate 自动建表。

## 处理过程

1. 参考 `.trae-context.md` 项目规范：MySQL 8 + Flyway 迁移；不使用 Hibernate 自动建表（schema 由 Flyway 管理）。
2. 通过 Glob 检查 `src/main/resources/**`，确认 Flyway 默认迁移目录 `src/main/resources/db/migration` 已存在。
3. 按要求编写纯 SQL 脚本并写入 `V1__init.sql`：
   - `job_audit`：id 为 BIGINT 自增主键，含 input_text / company_name / status / created_at 字段
   - `audit_signal`：id 为 BIGINT 自增主键，audit_id 通过外键 `fk_audit_signal_job_audit` 关联 `job_audit(id)`，含 signal_type / severity / reason / confidence / source / created_at 字段
   - 两表均指定 `ENGINE=InnoDB` 与 `DEFAULT CHARSET=utf8mb4`
4. 给出配套建议：在 `application.properties` 中确认 `spring.jpa.hibernate.ddl-auto=none`（或 validate），避免 Hibernate 自动建表与 Flyway 冲突。

## 生成的完整代码

`src/main/resources/db/migration/V1__init.sql`：

```sql
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
```

## 结果

- 迁移脚本已创建，位于 Flyway 默认扫描目录，应用启动时会自动执行 V1 迁移。
- 脚本为纯 SQL，无 Java 代码，符合"Flyway 管理 schema"的项目规范。
