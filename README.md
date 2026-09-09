# ArchGuard Platform

ArchGuard 的核心业务平台，初期采用 Java/Spring Boot 模块化单体。

## 当前状态

M0 仓库基线和 [M2 Platform 骨架与 Project 最小垂直切片设计](docs/technical-design/m2-platform-skeleton-and-project-slice.md)已建立。M2 首个实现切片提供 Java 21/Spring Boot 模块化单体、Flyway/PostgreSQL、Project 创建与成员查询、统一错误、traceId、审计写入和健康探针。

## 职责

- 管理用户、项目、代码仓库、规则、ADR 元数据、扫描任务和审计。
- 提供版本化 REST API、统一错误结构和业务权限校验。
- 编排扫描工作流并保存业务事实和可追溯结果。
- 维护 identity、project、repository、policy、scan、result、architecture、audit 等领域模块边界；模块只在出现真实职责时物化。

## 非职责

- 不实现源码、字节码或依赖图分析；这属于 `archguard-scanner`。
- 不承担 MCP 工具发现、路由或限流；这属于 `archguard-mcp-gateway`。
- 不保存部署密钥，也不把数据库、Redis 或内部端口暴露到公网。
- 初期不拆分为微服务，不为展示技术而引入 Kafka 或 Redis。

## 依赖与契约

- 通过版本化 Scanner 契约集成 `archguard-scanner`，不得依赖其内部类。
- PostgreSQL 是业务事实来源；模块之间通过公开应用接口或事件协作，不直接跨模块写表。
- 跨仓库架构与工程规范以 [archguard-docs](https://github.com/AI-ArchGuard/archguard-docs) 为准。

## 本地验证

前置要求：JDK 21 和已运行的 Docker。Maven 由 Wrapper 固定为 3.9.16，首次运行会下载 Maven 和项目依赖；集成测试通过 Testcontainers 启动 PostgreSQL 17.11。

Windows：

```powershell
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

Linux/macOS：

```bash
./mvnw verify
./mvnw spring-boot:run
```

复制 `.env.example` 中的非敏感配置并通过环境变量提供本地 PostgreSQL 连接后，可以启动应用。应用启动后可验证：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health/liveness
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
```

只有 health、liveness 和 readiness 对外暴露，详细组件信息关闭；readiness 聚合应用就绪状态和数据库状态。Project API 为 `POST /api/v1/projects` 与 `GET /api/v1/projects/{projectId}`，静态契约见 [OpenAPI](openapi/platform-v1.yaml)。默认 profile 不提供临时用户或不可信的自报身份，因此在正式认证适配器接入前，Project API 对实际请求保持失败关闭。

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP 监听端口；公网暴露仍由部署边界控制。 |
| `ARCHGUARD_SHUTDOWN_TIMEOUT` | `20s` | 优雅关闭阶段的最长等待时间，使用 Spring Duration 格式。 |
| `ARCHGUARD_DB_URL` | 无 | PostgreSQL JDBC URL，必填。 |
| `ARCHGUARD_DB_USERNAME` | 无 | PostgreSQL 运行账户，必填。 |
| `ARCHGUARD_DB_PASSWORD` | 无 | PostgreSQL 密码，必填且不得提交。 |
| `ARCHGUARD_DB_POOL_MAX_SIZE` | `10` | 数据库连接池最大连接数。 |
| `ARCHGUARD_DB_POOL_MIN_IDLE` | `1` | 数据库连接池最小空闲连接数。 |

`.env.example` 只包含非敏感示例；应用不会自动读取 `.env`。当前没有本地认证 profile，测试身份只存在于测试进程中，不可用于生产。

仓库级检查：

```bash
git diff --check
git status --short
```
