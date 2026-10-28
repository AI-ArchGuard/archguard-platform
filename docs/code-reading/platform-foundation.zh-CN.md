# 身份、追踪、审计与启动阅读指南

这些模块被业务流程共同使用。建议在读完 [Project](project-module.zh-CN.md) 后，按一次请求的顺序阅读。

1. [ArchGuardPlatformApplication](../../src/main/java/io/github/aiarchguard/platform/ArchGuardPlatformApplication.java) 启动 Spring Boot 和调度器，具体能力仍由配置和业务前置条件控制。
2. [TraceIdFilter](../../src/main/java/io/github/aiarchguard/platform/common/TraceIdFilter.java) 建立有格式限制的 traceId，写回响应，并在 finally 恢复线程上下文。
3. [PlatformSecurityConfiguration](../../src/main/java/io/github/aiarchguard/platform/identity/security/PlatformSecurityConfiguration.java) 定义无状态安全基线；[OidcSecurityConfiguration](../../src/main/java/io/github/aiarchguard/platform/identity/security/OidcSecurityConfiguration.java) 在显式 profile 下装配 JWT 校验。健康检查公开，Webhook 由其签名验证链继续保护，其余业务请求需要认证。
4. [SpringSecurityCurrentActorProvider](../../src/main/java/io/github/aiarchguard/platform/identity/security/SpringSecurityCurrentActorProvider.java) 把已认证上下文转换成 [CurrentActor](../../src/main/java/io/github/aiarchguard/platform/identity/CurrentActor.java)。全局权限和 Project 成员授权分开处理，不能从请求体自报身份。
5. [ApiError](../../src/main/java/io/github/aiarchguard/platform/common/ApiError.java)、[ApiErrorWriter](../../src/main/java/io/github/aiarchguard/platform/common/ApiErrorWriter.java) 与 [GlobalExceptionHandler](../../src/main/java/io/github/aiarchguard/platform/common/GlobalExceptionHandler.java) 分别定义错误结构、过滤器错误写入和 MVC 兜底映射。业务处理器拥有更具体的错误语义。

## 请求转入后台

[OidcTaskScope](../../src/main/java/io/github/aiarchguard/platform/identity/security/OidcTaskScope.java) 只捕获 UUID、权限和到期时间，返回一次性任务；不复制原始 Token 或可变 SecurityContext。执行时重新校验到期时间，任务不能递归捕获新身份来延长权限。

[TraceTaskScope](../../src/main/java/io/github/aiarchguard/platform/common/TraceTaskScope.java) 只传递指定 traceId，并隔离线程池遗留 MDC。这解决追踪串线问题，不能替代业务授权。两个范围都在 finally 恢复线程原状态。

## 审计为何有两种写法

[AuditRecorder](../../src/main/java/io/github/aiarchguard/platform/audit/AuditRecorder.java) 区分普通记录与显式有界记录。[JdbcAuditRecorder](../../src/main/java/io/github/aiarchguard/platform/audit/persistence/JdbcAuditRecorder.java) 普通写入沿用调用时的事务；有界路径要求事务管理器与数据源一致，以新事务和数据库超时约束等待，并在提交前后检查剩余预算。

单调时钟和初始预算防止调用方给等待续期。提交确认过晚会拒绝后续凭据消费，但不能声称数据库中已提交的审计被撤销。metadata 的最小化由调用方负责，审计端口不是凭据自动脱敏器。

可从 [OidcTaskScopeTest](../../src/test/java/io/github/aiarchguard/platform/identity/security/OidcTaskScopeTest.java)、[TraceTaskScopeTest](../../src/test/java/io/github/aiarchguard/platform/common/TraceTaskScopeTest.java)、[BoundedAuditRecorderIntegrationTest](../../src/test/java/io/github/aiarchguard/platform/BoundedAuditRecorderIntegrationTest.java) 阅读这些不变量。身份测试中的本地令牌与模拟上下文不能代表生产 OIDC 联调。
