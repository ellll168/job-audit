# 会话记录：第一周总结（架构梳理 + 面试拷问 + README）

- 项目：job-audit（岗位反向尽调助手）
- 会话日期：2026-11-04
- 涉及文件：`README.md`（新建）

---

## 用户提问

> @.trae-context.md 第一周的开发结束了。请帮我做两件事：
> 1. 画一个文本版的数据流转图：描述一下当一个 POST /api/audits 请求进来时，它依次经过了哪些类（从 Filter 到 Controller，再到 RuleEngine，最后落库），并说明每个类的作用是什么。
> 2. 扮演大厂面试官，针对我第一周写的代码，向我提出 5 个深度拷问。比如关于线程池的 TraceId、Spring 的依赖注入、或者事务管理方面的问题。

## 一、POST /api/audits 数据流转图（文本版）

```
POST /api/audits {"companyName":"...","inputText":"..."}
   │
   ▼
┌─ TraceIdFilter (OncePerRequestFilter) ──────────────┐
│ 请求入口：UUID → MDC.put("traceId")                   │
│ finally：响应发出后 MDC.clear()                       │
└──────────────────────┬───────────────────────────────┘
                       ▼
┌─ JobAuditController.create(@Validated @RequestBody AuditRequest)
│ ① Bean Validation：@NotBlank 失败 → MethodArgumentNotValidException
│ ② log.info（traceId 由日志格式 %X{traceId} 自动带出）
│ ③ 组装 JobAudit 实体（status=SCANNED, createdAt=now）
└──────────────────────┬───────────────────────────────┘
                       ▼
┌─ JobAuditMapper (MP BaseMapper) ─────────────────────┐
│ insert → job_audit 表，自增主键回填到实体               │
└──────────────────────┬───────────────────────────────┘
                       ▼
┌─ AuditRuleEngine.analyze(inputText) (@Component) ────┐
│ 6 条硬规则正则扫描 → List<AuditSignal>（内存值对象）    │
└──────────────────────┬───────────────────────────────┘
                       ▼
┌─ Controller.toRecord() 转换 ─────────────────────────┐
│ AuditSignal → AuditSignalRecord（补 auditId 外键、      │
│ source="RULE"、confidence=null）                      │
└──────────────────────┬───────────────────────────────┘
                       ▼
┌─ AuditSignalRecordMapper (MP BaseMapper) ────────────┐
│ 逐条 insert → audit_signal 表                          │
└──────────────────────┬───────────────────────────────┘
                       ▼
   返回 AuditResponse → Jackson 序列化 JSON → 200 响应

旁路：GlobalExceptionHandler (@RestControllerAdvice)
  任一层抛异常 → 拦截 → ErrorResponse{code,message,timestamp}
  400 参数校验失败 / 404 记录不存在 / 500 兜底（堆栈只进日志）
```

各类职责一句话：

| 类 | 职责 |
|---|---|
| TraceIdFilter | 请求级 traceId 生命周期的唯一管理者 |
| JobAuditController | 编排：校验 → 入库 → 规则扫描 → 信号落库 → 返回 |
| AuditRuleEngine | 纯业务规则，无副作用，可独立单测 |
| 两个 Mapper | 数据访问，零 SQL（MyBatis-Plus 托管） |
| GlobalExceptionHandler | 统一错误格式，屏蔽堆栈 |

## 二、大厂面试官 5 连拷问

**Q1（线程池 × TraceId）**："你的 traceId 放在 MDC 里，本质是 ThreadLocal。如果我把 analyze() 改成 @Async 或者用 CompletableFuture 并行跑 6 条规则，子线程的日志里 traceId 会变成什么？怎么解决？"
- 考察点：ThreadLocal 不跨线程传递。
- 思路点拨：子线程 MDC 为空 → 手动把 MDC context map 拷进任务、Spring 的 TaskDecorator、或阿里 TransmittableThreadLocal。

**Q2（事务管理）**："create() 里 job_audit 已经 insert 成功，第 3 条 signal 落库时数据库断了。现在库里是什么状态？前端拿到的响应是什么？你会怎么修？"
- 考察点：当前没有事务——主表脏数据、信号残缺，前端收 500。
- 思路点拨：@Transactional 加在 create() 上；追问：自增 id 回滚后不复用（InnoDB 自增计数不回退，产生 id 空洞）。

**Q3（依赖注入）**："为什么 Controller 用构造器注入而不是字段上直接 @Autowired？如果容器里有两个 AuditRuleEngine 类型的 Bean 会怎样？"
- 考察点：构造器注入保证不可变、可写纯单元测试、Bean 缺失启动即失败；字段注入隐藏依赖。
- 思路点拨：Bean 冲突 → NoUniqueBeanDefinitionException，用 @Primary / @Qualifier 解决。

**Q4（Filter 语义）**："为什么继承 OncePerRequestFilter 而不是直接实现 Filter？MDC.clear() 和 MDC.remove(\"traceId\") 各有什么风险？"
- 考察点：OncePer 保证一次请求只过滤一次（普通 Filter 在 forward/include/异步 dispatch 会重复进入，traceId 被覆盖）。
- 思路点拨：clear() 会清掉别人放的 key（如以后的 userId）；remove 更精确；选 clear 的前提是 Filter 是 MDC 唯一写入方。

**Q5（规则引擎扩展性）**："产品下周要加 30 条规则，还要支持后台动态开关每一条。现在这个结构怎么演进？"
- 考察点：开闭原则。
- 思路点拨：抽象 AuditRule 接口（`Optional<AuditSignal> apply(String jdText)`），引擎持有 List<AuditRule> 自动注入所有实现循环调用；开关走配置位或 DB 规则表。从"硬编码 if 链"演进到"策略模式 + 注册表"。

**用户答案**：待补充（用户后续作答后追加到此节）。

**落地建议**：5 个问题中 Q2（事务）是目前唯一会产生脏数据的实际问题，建议下周优先落地；其余偏架构储备。

## 三、生成 README.md

### 用户提问

> 请根据目前项目的实际情况，帮我生成一份标准的 README.md。包含项目简介（岗位反向尽调助手）、目前的技术栈（Spring Boot 3 + MyBatis-Plus + MySQL）、以及本地如何启动的步骤。

### 产出

已生成 `README.md`（项目根目录），内容与项目实际状态对齐：

- 项目简介 + 6 条规则清单（含 severity 分级）
- 技术栈表：JDK 17 / Spring Boot 3.5.7 / MyBatis-Plus 3.5.9 / MySQL / Flyway / Maven / SLF4J+MDC
- 架构一览（文本流转图 + 两张表结构）
- 本地启动 4 步：`CREATE DATABASE job_audit_db`（表由 Flyway 自动迁移）→ 改 `application.properties` 账号密码 → `mvn spring-boot:run` → curl `/api/health` 验证
- 接口清单（GET /api/health、POST /api/audits、GET /api/audits/{id}）
- 测试说明（`mvn test`，需本地 MySQL 在线）
- 开发规约 4 条（源自 .trae-context.md：无 Lombok / Flyway 管理 schema / 统一异常 / traceId 由 Filter 管理）

### 遗留提示

后续接入 LLM 模块或 Vue3 前端时，README 的架构图与技术栈表需同步更新。
