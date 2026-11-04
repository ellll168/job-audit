# job-audit 岗位反向尽调助手

输入一段招聘 JD，帮你识别其中的风险信号——薪资不透明、职责模糊、要求矛盾、紧迫话术、招转培骗局、超长待机等，辅助求职者"反向尽调"一家公司。

## 技术栈

| 组件 | 版本 / 说明 |
|---|---|
| JDK | 17 |
| Spring Boot | 3.5.7（Web + Validation） |
| MyBatis-Plus | 3.5.9（零 XML，BaseMapper 托管 CRUD） |
| MySQL | 8+（本地开发实测 MySQL 9.0） |
| Flyway | 数据库 schema 迁移管理 |
| Maven | 构建工具 |
| 日志 | SLF4J + MDC traceId 链路追踪 |

## 架构一览

```
POST /api/audits
  → TraceIdFilter          生成 traceId 放入 MDC，请求结束统一清理
  → JobAuditController     参数校验 → 主表入库 → 规则扫描 → 信号落库 → 返回
  → AuditRuleEngine        6 条硬规则正则扫描（无副作用，可独立单测）
  → JobAuditMapper / AuditSignalRecordMapper   MyBatis-Plus 数据访问
  → GlobalExceptionHandler 统一错误格式（400 / 404 / 500，堆栈不泄漏给前端）
```

**数据库表**（Flyway 脚本 `src/main/resources/db/migration/V1__init.sql`）：

- `job_audit`：审计主表（id / input_text / company_name / status / created_at）
- `audit_signal`：风险信号表（id / audit_id 外键 / signal_type / severity / reason / confidence / source / created_at）

**规则清单**：`salary_opacity`(HIGH) / `vague_duty`(MEDIUM) / `contradictory_req`(MEDIUM) / `urgency_noise`(LOW) / `scam_risk`(HIGH) / `overwork_risk`(MEDIUM)

## 本地启动

前置条件：JDK 17、Maven、本地 MySQL 已启动。

1. 创建数据库（表结构由 Flyway 自动迁移，无需手动建表）：

```sql
CREATE DATABASE job_audit_db DEFAULT CHARSET utf8mb4;
```

2. 修改 `src/main/resources/application.properties` 中的数据库账号密码：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/job_audit_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8
spring.datasource.username=root
spring.datasource.password=你的密码
```

3. 启动：

```bash
mvn spring-boot:run
```

4. 验证：

```bash
curl http://localhost:8080/api/health
# {"status":"ok","project":"job-audit"}
```

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查 |
| POST | `/api/audits` | 提交 JD 审计，body：`{"companyName":"...","inputText":"..."}` |
| GET | `/api/audits/{id}` | 按 ID 查询审计记录及关联信号 |

## 测试

```bash
mvn test
```

注意：`@SpringBootTest` 会连接真实 MySQL 并执行 Flyway 校验，跑测试前请确保 MySQL 已启动。

## 开发规约

- 不使用 Lombok，实体类手写 getter/setter，DTO 优先用 Java 17 record
- 数据库结构变更只走 Flyway（`db/migration`），禁止 Hibernate 自动建表（`ddl-auto=none`）
- 异常统一走 `GlobalExceptionHandler`，不裸抛 500
- 日志排查依赖 MDC 中的 `traceId`，由 `TraceIdFilter` 统一管理
