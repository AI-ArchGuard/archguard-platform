# ArchGuard Platform

ArchGuard 的核心业务平台，初期采用 Java/Spring Boot 模块化单体。

## 当前状态

M0 仓库基线、[M2 Platform 骨架与 Project 最小垂直切片设计](docs/technical-design/m2-platform-skeleton-and-project-slice.md)和 [OIDC 与 Project 生命周期/成员管理设计](docs/technical-design/m2-oidc-and-project-management.md)已建立。当前实现提供 Java 21/Spring Boot 模块化单体、Flyway/PostgreSQL、OIDC JWT 认证、Project 生命周期与成员治理、统一错误、traceId、审计写入和健康探针。

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

只有 health、liveness 和 readiness 对外暴露，详细组件信息关闭；readiness 聚合应用就绪状态和数据库状态。Project API 支持创建、分页列表、单项查询、名称修改、删除和成员列表/增改删，静态契约见 [OpenAPI](openapi/platform-v1.yaml)。默认 profile 不提供临时用户或不可信的自报身份，因此 Project API 失败关闭；生产请求必须显式启用 `oidc` profile。

OIDC token 必须由配置的 HTTPS issuer 签发，`aud` 包含配置的 Platform audience，`sub` 是 UUID，`scope`/`scp` 携带权限（创建 Project 需要 `project:create`）。启动示例：

```powershell
$env:SPRING_PROFILES_ACTIVE='oidc'
$env:ARCHGUARD_OIDC_ISSUER_URI='https://identity.example.com/realms/archguard'
$env:ARCHGUARD_OIDC_AUDIENCE='archguard-platform'
.\mvnw.cmd spring-boot:run
```

可选的 `ARCHGUARD_OIDC_JWK_SET_URI` 用于显式指定 HTTPS JWKS 地址；省略时从 issuer discovery 获取。示例地址仅说明配置格式，不是可用租户或凭据。

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
| `ARCHGUARD_OIDC_ISSUER_URI` | 无；`oidc` profile 必填 | 唯一可信 OIDC issuer，必须是绝对 HTTPS URI。 |
| `ARCHGUARD_OIDC_AUDIENCE` | 无；`oidc` profile 必填 | Platform JWT audience。 |
| `ARCHGUARD_OIDC_JWK_SET_URI` | 无 | 可选 HTTPS JWKS 地址；省略时执行 issuer discovery。 |

`.env.example` 只包含非敏感示例；应用不会自动读取 `.env`。测试身份只存在于测试进程中，不可用于生产；应用不接受 `X-Actor-Id` 等自报身份头。

仓库级检查：

```bash
git diff --check
git status --short
```
