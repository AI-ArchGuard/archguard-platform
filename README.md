# ArchGuard Platform

ArchGuard 的核心业务平台，初期采用 Java/Spring Boot 模块化单体。

## 当前状态

Platform MVP `v0.3.0` 控制面已建立。当前实现提供 Java 21/Spring Boot 模块化单体、Flyway/PostgreSQL、OIDC JWT、Project/Repository/RuleSet/ScanJob/Finding 生命周期、统一错误、traceId、审计、文件邮箱 Runner 编排与健康探针。设计见 [Platform MVP Technical Design](docs/technical-design/v0.3-platform-mvp.md)。

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

阶段 3B 的[持续治理契约设计](docs/technical-design/v0.4-governance-3b-contracts.md)和[报告提交/门禁 OpenAPI 扩展](openapi/governance-v1.json)已冻结，阶段 3E 实现其运行时入口。阶段 3C 的[不可变基线与分类设计](docs/technical-design/v0.4-governance-3c-baselines.md)及[运行时基线 API](openapi/governance-baselines-v1.json)支持成功扫描基线与 `NEW`、`EXISTING`、`RESOLVED` 分类。阶段 3D 的[门禁与例外设计](docs/technical-design/v0.4-governance-3d-gates.md)及[运行时门禁 API](openapi/governance-gates-v1.json)提供版本化门禁和有期限例外。阶段 3E 的[GitHub/CI 设计](docs/technical-design/v0.4-governance-3e-github-ci.md)与[GitHub Adapter API](openapi/governance-github-v1.json)连接签名事件和 CI 报告；Platform 仍不保存 Git 凭据。阶段 3F 的[治理只读视图设计](docs/technical-design/v0.4-governance-3f-read-api.md)和[固定读取契约](openapi/governance-read-v1.json)供 Web 查询 PR、门禁历史和分类详情。

- 通过版本化 Scanner 契约集成 `archguard-scanner`，不得依赖其内部类。
- PostgreSQL 是业务事实来源；模块之间通过公开应用接口或事件协作，不直接跨模块写表。
- 跨仓库架构与工程规范以 [archguard-docs](https://github.com/AI-ArchGuard/archguard-docs) 为准。
- 当前阶段与冻结边界以 Docs 的 `product/roadmap.md` 和 ADR-0006 为准；旧 M0–M13 编号只作为历史设计上下文。

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

只有 health、liveness 和 readiness 对外暴露，详细组件信息关闭；readiness 聚合应用就绪状态和数据库状态。版本化 REST 契约见 [OpenAPI](openapi/platform-v1.yaml)。默认 profile 不提供临时用户或不可信的自报身份，因此业务 API 失败关闭；生产请求必须显式启用 `oidc` profile。

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
| `ARCHGUARD_OIDC_ALLOW_HTTP` | `false` | 仅 `local-compose` profile 可设为 `true`。 |
| `ARCHGUARD_SOURCE_ROOT` | `./sources` | Repository 可注册的唯一受控源码根。 |
| `ARCHGUARD_SCANNER_JAR` | `/opt/archguard/scanner.jar` | 固定的 Scanner `v0.2.1` JAR。 |
| `ARCHGUARD_RUNNER_MAILBOX` | `./runner-mailbox` | Platform 与无网络 Runner 共享的版本化文件邮箱。 |
| `ARCHGUARD_RUNNER_LEASE` | `5m` | 任务 attempt 租约。 |
| `ARCHGUARD_RUNNER_MAX_ATTEMPTS` | `2` | Runner 故障后的最大认领次数。 |
| `ARCHGUARD_GITHUB_WEBHOOK_SECRET` | 无；Webhook 入口失败关闭 | GitHub Webhook HMAC secret，仅运行环境注入，不写入 Git。 |

`.env.example` 只包含非敏感示例；应用不会自动读取 `.env`。测试身份只存在于测试进程中，不可用于生产；应用不接受 `X-Actor-Id` 等自报身份头。

仓库级检查：

```bash
git diff --check
git status --short
```
## 许可证

本仓库采用 [Apache License 2.0](LICENSE)。
