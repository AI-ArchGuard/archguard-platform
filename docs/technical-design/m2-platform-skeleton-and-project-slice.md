# Technical Design：M2 Platform 骨架与 Project 最小垂直切片

- Issue：[archguard-platform#7](https://github.com/AI-ArchGuard/archguard-platform/issues/7)
- Owner：ArchGuard 项目所有者
- 状态：Accepted（允许开始 M2 实现，不表示 M2 已完成）
- 目标里程碑：M2
- 需求与场景：`V1-FR-001` / `AC-AUTH-001`、`V1-NFR-003` / `AC-AUTH-002`，并为 `V1-FR-012` / `AC-AUDIT-001` 和 `V1-NFR-006` / `AC-OBS-001` 提供首批证据
- 依赖决策：G4、ADR-0004、ADR-0005
- 最后评审：2026-09-09（无阻断项）

## 背景与约束

Platform 当前只有仓库治理基线，没有 Maven 工程、可启动应用、数据库迁移或业务 API。M2 的目标不是一次铺开全部 V1 控制面，而是建立一个可重复构建、可验证模块边界、可从空库迁移的 Spring Boot 模块化单体，并用 Project 创建/查询证明领域、应用、Web、授权、持久化和审计边界能够端到端工作。

本设计必须同时满足以下约束：

- Platform 是一个部署制品内的模块化单体；没有容量、团队或隔离证据时不拆微服务。
- PostgreSQL 是业务事实的唯一持久来源；只有 Platform 基础设施适配器访问数据库。
- 模块不得访问其他模块的内部类或表；跨模块只使用公开应用接口或显式事件。
- 领域层不依赖 Spring Web、Spring JDBC、数据库驱动、Jackson 或外部模型 SDK。
- 事务边界位于应用层，控制器只负责协议转换，数据库行模型不得成为 API DTO。
- M2 只交付 Project 最小切片，不宣称 Repository、Scan、Scanner 或分析能力已支持。
- 所有身份均来自已验证的安全主体；不得信任客户端自报 actor 请求头。
- 迁移一旦合并只允许追加，不允许原地改写。

## 当前状态

- G1–G4 已通过，D14 为 Ready，D15–D16 与 ADR-0004/0005 为 Accepted。
- `archguard-platform` 的 M2 分支从最新 `main` 创建，建立 Issue 时工作树干净。
- 仓库尚无 `pom.xml`、Maven Wrapper、应用源码、迁移或测试，当前不能运行 `mvnw verify`。
- D14 将具体 API、表结构和角色名留给 M2/M3；D16 要求 M2 交付 `V1-FR-001`、授权骨架、统一错误、traceId、审计端口和领域依赖检查。

## 建议方案

### 运行时与构建基线

| 项目 | M2 固定值 | 选择依据 |
|---|---|---|
| Java | 21 LTS | 位于 D14 的 Java 17/21 边界内；相比 Java 17 提供更长的新项目演进窗口，又不引入 V1 明确排除的 Java 25 输入承诺。Platform 运行时版本不代表 Scanner 自动支持相同语言级别。 |
| Maven Wrapper | 3.9.16 | 采用评审日 Maven 3 的当前稳定版；不使用仍为预览的 Maven 4。 |
| Spring Boot | 3.5.16 | G4 已接受 Boot 3.5 兼容线；官方系统要求支持 Java 21。 |
| Spring Modulith | 1.4.13 | 官方兼容矩阵将 Modulith 1.4 对应到 Boot 3.5；只用于模块模型和测试，不启用事件存储。 |
| PostgreSQL | 17.x；集成测试固定 17.11 | PostgreSQL 17 仍受官方支持至 2029-11-08；M2 固定当前 minor 做可复现测试，生产补丁版本应及时升级且不改变 SQL 契约。 |
| PostgreSQL JDBC | 42.7.11 | 使用 Boot 3.5.16 BOM 管理版本，不在子依赖中单独覆盖。 |
| Testcontainers | 1.21.4 | 使用 Boot 3.5.16 BOM 管理版本；不直接跨到存在模块与包名破坏性迁移的 2.x。 |
| Flyway | 11.7.2 | 使用 Boot 3.5.16 BOM 管理版本，并显式加入 PostgreSQL 数据库模块。 |

版本依据以评审日官方资料为准：[Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Spring Modulith 兼容矩阵](https://docs.spring.io/spring-modulith/reference/appendix.html)、[Spring Boot 3.5 依赖坐标](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html)、[Maven 下载页](https://maven.apache.org/download.cgi)和[PostgreSQL 版本策略](https://www.postgresql.org/support/versioning/)。版本升级必须通过依赖树、架构测试和 PostgreSQL 集成测试，不随上游“latest”自动漂移。

### 制品与模块策略

M2 使用一个 Maven module、一个 Spring Boot 应用和一个可执行制品。业务模块由 `io.github.aiarchguard.platform` 下的直接子包表示，使用 Spring Modulith 验证依赖：

```text
io.github.aiarchguard.platform
├── ArchGuardPlatformApplication + 协议级错误/trace/安全装配
├── identity     已物化：把已认证 principal 映射为 CurrentActor
├── project      已物化：Project 创建、查询、成员授权与持久化
└── audit        已物化：窄 AuditRecorder 接口与 PostgreSQL 写入
```

G2 的完整模块目录固定为 `identity`、`project`、`repository`、`policy`、`scan`、`result`、`architecture`、`audit`。M2 只物化前三个有真实职责的模块；`repository`、`policy`、`scan`、`result`、`architecture` 在出现用例时建立，不创建空 controller/service/repository 占位类。原 README 中宽泛的 `analysis` 由 `policy`、`scan`、`result` 三个所有权更清楚的候选模块取代。

允许的初始依赖图为：

```text
project ──> identity
   │
   └──────> audit

identity ──> 无业务模块
audit    ──> 无业务模块
```

- 模块根包只放公开应用接口及其输入/输出类型；`application`、`domain`、`web`、`persistence` 等子包均为模块内部实现。
- `project` 不能读取 `identity` 或 `audit` 的表；只调用各自公开接口。
- 应用根包可包含少量跨协议装配，如统一错误 DTO、trace filter 和 Security 配置，但禁止放入领域术语或形成无边界的 shared 工具箱。
- Spring Modulith `ApplicationModules.verify()` 检查模块循环和内部包访问；ArchUnit 补充领域层禁止依赖和字段注入规则。

## 组件与职责

| 组件 | 职责 | 禁止职责 |
|---|---|---|
| Root bootstrap/web support | 启动、统一错误映射、traceId、SecurityFilterChain、Actuator 暴露策略 | 业务判断、直接 SQL、保存身份 |
| `identity` | 从 Spring Security 的已认证 principal 读取不可变 UUID `sub` 和已验证权限，返回 `CurrentActor`；提供仅本地 profile 的显式认证适配器 | 用户注册、密码存储、组织模型、信任 `X-Actor-Id` |
| `project.web` | `/api/v1/projects` 的请求校验、用例调用、HTTP 状态和 DTO 映射 | 事务、成员授权、异常细节输出 |
| `project.application` | 创建/查询用例、应用事务、成员校验、冲突映射、审计调用 | Servlet/JDBC 类型、跨模块表访问 |
| `project.domain` | `Project`、`ProjectId`、`ProjectKey`、`ProjectName`、`ProjectRole` 及不变量 | Spring、Jackson、JDBC 注解和时间/ID 的隐式全局读取 |
| `project.persistence` | 使用 Spring JDBC 执行显式 SQL，把 row 与领域对象互转 | 返回数据库 row 给 Web、写 audit schema |
| `audit` | 接收最小化审计命令并在自有 schema 写入追加记录 | 查询 Project 表、保存凭据/源码/请求体、提供 M2 公共查询 API |

所有组件采用构造器注入。ID 与时钟通过应用端口注入，使领域测试不依赖随机数或系统时间。

## API、事件和数据模型

### REST API v1

`POST /api/v1/projects`

```json
{
  "key": "payments-core",
  "name": "Payments Core"
}
```

成功返回 `201 Created`，`Location: /api/v1/projects/{id}`：

```json
{
  "id": "9af6f915-2f4d-4a32-9b62-45788bdcf17c",
  "key": "payments-core",
  "name": "Payments Core",
  "createdAt": "2026-09-09T08:00:00Z"
}
```

`GET /api/v1/projects/{projectId}` 对当前 actor 是 `MAINTAINER` 或 `VIEWER` 时返回相同 DTO。未认证返回 `401`；已认证但无成员关系与资源不存在统一返回 `404 project.not_found`，避免枚举资源存在性。

M2 不实现列表、修改、删除和成员管理。创建者在同一事务中获得 `MAINTAINER` 角色；`VIEWER` 仅由集成测试 fixture 建立，用于证明读取矩阵，公共成员管理留给后续设计。角色直接采用 D14 语义，不额外引入 OWNER 的隐含权限层次。

Project key 是当前顶层治理边界的全局稳定标识：小写 ASCII，匹配 `^[a-z][a-z0-9-]{2,62}$`，创建后不可变且全局唯一。名称先 trim、NFC 规范化，再要求 1–120 个 Unicode code point 且不含控制字符。ID 为服务端生成的 UUID v4，时间为 UTC `Instant`。重复 key 使用冲突语义而非 M2 幂等记录，返回 `409 project.key_conflict`；不返回冲突 Project 的 ID 或所有者。

所有非 2xx 响应使用同一结构，包括 Spring Security 在控制器之前产生的 `401/403`；自定义 `AuthenticationEntryPoint` 和 `AccessDeniedHandler` 复用统一错误写入器：

```json
{
  "code": "project.key_conflict",
  "message": "A project with this key already exists.",
  "traceId": "4c4b01d9b77d4fd0bb61736bb9880153",
  "details": {}
}
```

`code` 是稳定机器字段；`message` 不携带内部异常；`details` 只包含安全、字段级验证信息。静态契约保存为仓库根目录的 `openapi/platform-v1.yaml`，不作为静态 Web 资源暴露，也不引入运行时文档 UI。MockMvc 契约测试必须覆盖路径、状态、字段和错误示例，降低手写契约漂移风险。

### 身份和授权边界

- `CurrentActorProvider` 只接受 `SecurityContext` 中 `isAuthenticated=true`、`sub` 可解析为 UUID 且权限来自可信认证适配器的主体；缺失或非法主体失败关闭。应用层再次检查窄权限 `project:create`，不能只依赖 controller 注解。
- 默认 profile 不生成默认密码，也不接受自报身份；未配置可信认证适配器时 Project API 全部 `401`，但健康探针仍可用。
- `local` profile 可用 HTTP Basic 做人工 smoke test，但用户名、密码和固定 actor UUID 全部从环境变量注入；缺任一值则启动失败。该本地主体被明确授予 `project:create`，该 profile 不能作为部署配置。
- 集成测试使用 `spring-security-test` 注入两个固定 actor，不绕过应用层成员授权。
- 创建权限：已认证主体还必须具有 `project:create`；查询权限：必须存在该 Project 的成员记录。组织级准入和配额不在 M2 范围，后续引入时不得削弱现有资源归属检查。

### PostgreSQL 数据模型

数据库名为 `archguard`。首个 append-only 迁移 `V1__create_project_and_audit_schemas.sql` 只创建实际有持久数据的 `project` 与 `audit` schema；`identity` 在 M2 无持久事实，因此不创建空 schema 或用户表。实际业务表如下：

| Schema.Table | 关键字段 | 约束与所有者 |
|---|---|---|
| `project.projects` | `id uuid`、`project_key varchar(63)`、`name varchar(120)`、`created_at timestamptz`、`created_by uuid`、`version bigint` | `id` PK；`project_key` unique；仅 project adapter 读写 |
| `project.project_members` | `project_id uuid`、`actor_id uuid`、`role varchar(32)`、`created_at timestamptz` | 复合 PK；同 schema FK 到 projects；role check 为 `MAINTAINER/VIEWER` |
| `audit.audit_records` | `id uuid`、`occurred_at timestamptz`、`actor_id uuid`、`project_id uuid null`、`action varchar(80)`、`result varchar(24)`、`trace_id varchar(32)`、`metadata jsonb` | append-only；不对 project 表建跨模块 FK；仅 audit adapter 写入 |

数据库列使用 snake_case，所有时间为 `timestamptz`。应用数据库账户拥有三 schema 的最小 DML 权限；迁移账户与运行账户分离由 M11 部署设计完成，M2 本地/CI 可使用同一临时账户但不得把凭据提交到仓库。

Spring JDBC 而非 ORM 映射持久化，以保持聚合和 SQL 边界显式。唯一约束是并发创建冲突的最终事实来源；应用层的预检查只改善正常路径，不能替代数据库约束。`version` 为后续更新的乐观锁入口，M2 不提供更新 API。

### 应用调用和审计

`CreateProjectUseCase` 在单个应用事务内：

1. 获取当前 actor 并在应用层校验 `project:create`。
2. 构造 Project 领域对象并验证 key/name。
3. 插入 `project.projects`。
4. 插入创建者的 `MAINTAINER` 成员记录。
5. 调用公开 `AuditRecorder` 写入 `project.created / SUCCESS`。
6. 提交后返回应用 DTO；任一步失败全部回滚。

查询用例先按 `projectId + actorId` 联合查询，避免先查 Project 再授权形成存在性侧信道。对无权/不存在统一返回 not found，并记录同样的最小化 `project.read_denied` 审计；审计失败不得把拒绝变成放行。成功创建的审计与业务写入共用事务，审计失败时创建整体回滚。冲突、未找到和拒绝等失败审计在业务事务结束后以独立短事务写入；写入再次失败时保留原始 `4xx`，同时产生高优先级结构化安全日志和计数，不能把拒绝改成允许。M2 不发布领域事件或 outbox；同库同步审计满足首个切片的一致性需求，异步化必须另有故障与重放设计。

## 关键流程与失败流程

### 创建成功

```text
HTTP -> Security -> CurrentActor -> CreateProjectUseCase (transaction)
                              -> project tables
                              -> AuditRecorder -> audit table
HTTP <- 201 + Project DTO <- commit
```

### 关键失败语义

| 条件 | HTTP / code | 数据与审计行为 |
|---|---|---|
| 未认证或主体无合法 UUID | `401 authentication.required` | 不写 Project；Security 记录脱敏拒绝事件 |
| 已认证但无 `project:create` | `403 authorization.denied` | 不写 Project；记录最小拒绝审计 |
| key/name 不合法 | `400 validation.failed` | 不开业务写事务；details 只给字段级原因 |
| 并发或重复 key | `409 project.key_conflict` | 业务事务回滚；随后以独立短事务记录不含冲突资源详情的失败审计 |
| Project 不存在或 actor 无成员关系 | `404 project.not_found` | 两种情况使用相同外部响应和审计动作；独立短事务写最小拒绝审计 |
| PostgreSQL 不可用 | `503 persistence.unavailable` | readiness 失败；错误不暴露 JDBC/SQL 信息 |
| 成功路径审计写失败 | `503 audit.unavailable` | 创建事务回滚；失败/拒绝路径的审计降级只影响证据并触发高优先级告警，原始拒绝保持不变 |
| 未处理异常 | `500 internal.error` | 生成安全响应并记录异常类别/traceId；不回传堆栈 |

## 安全与隐私

- Project API 默认认证，Actuator 仅暴露 health 组；环境、beans、configprops、heapdump 等端点不暴露。
- 输入 DTO 使用 allow-list 校验和明确大小上限；错误与日志不得包含 Authorization、Cookie、密码、完整请求体或源码。
- 不采信 `X-Actor-Id`、`X-Role` 等客户端身份头；反向代理身份传播在正式 Identity/OIDC ADR 后再启用。
- Unauthorized 与 not-found 响应保持稳定，查询 SQL 绑定 actor，测试两个 actor 的交叉访问。
- `local` 认证 profile 显式启用且凭据只从环境变量读取；CI 只用测试安全配置。
- SQL 全部参数化，不拼接 schema、列名或用户输入。
- 审计 metadata 使用固定 Schema 和 allow-list，不保存 Project name、凭据、请求体、源码或隐藏推理。
- 数据库、Actuator 和内部服务端口不得直接暴露公网；部署层网络策略留给 M11，但应用默认不额外开启管理端口。

## 可观测性

- 使用 Spring Boot 内置结构化 JSON 日志，不新增日志编码器。
- 请求入口接受的 `X-Request-Id` 只有在严格匹配 32 位小写十六进制时才复用，否则生成新的 128-bit 随机 traceId；响应头、错误 DTO、MDC 和审计记录使用同一值。
- M2 的 traceId 是请求关联 ID，不冒充完整的 W3C 分布式追踪；M5 引入跨进程传播时再评审 OpenTelemetry。
- 每个请求记录受控事件名、HTTP method、路由模板、状态、耗时、traceId；已认证时可记录 actor UUID，禁止记录 URL 查询原文、凭据和业务名称。
- Actuator liveness 只依赖应用存活状态；readiness 包含 PostgreSQL `db` 指标。默认应用端口为 `8080`，可由 `SERVER_PORT` 覆盖；management 使用同端口且只暴露 `/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness`，详情为 `never`。

## 配置契约

| 环境变量 | 必需性 | 含义与约束 |
|---|---|---|
| `ARCHGUARD_DB_URL` | 必需 | PostgreSQL JDBC URL，数据库名为 `archguard`；无内置生产默认值 |
| `ARCHGUARD_DB_USERNAME` | 必需 | 应用/本地迁移账户名 |
| `ARCHGUARD_DB_PASSWORD` | 必需、Secret | 只从环境注入，不记录 |
| `ARCHGUARD_DEV_USERNAME` | 仅 local 必需 | 本地 smoke test 用户名 |
| `ARCHGUARD_DEV_PASSWORD` | 仅 local 必需、Secret | 本地 smoke test 密码 |
| `ARCHGUARD_DEV_ACTOR_ID` | 仅 local 必需 | 固定 UUID，用于映射认证主体 |
| `SERVER_PORT` | 可选，默认 8080 | HTTP 监听端口；是否对外暴露由部署层决定 |

仓库只提交变量名和 `.env.example` 的空值/占位说明，不提交真实凭据或可用默认密码。

## 容量与性能估算

M2 没有真实用户量、Project 数或延迟基线，因此不虚构 SLO。当前路径每次创建包含三次有界写入，每次查询为按 `project_id + actor_id` 索引的单次联合读取，复杂度和连接占用可预测。没有列表接口、批量 API、缓存、Redis 或异步队列。

实现阶段记录 JDK/OS/PostgreSQL 版本、冷启动时间、`mvnw verify` 耗时和固定测试请求耗时作为非门禁基线。连接池保持 Boot 可配置实现，不在 M2 根据猜测扩容；出现真实并发/连接证据后再设预算。

## 依赖决策

| 依赖 | 范围、用途 | 许可证与维护状态 | 替代方案和取舍 |
|---|---|---|---|
| Spring Boot Web/Validation/Actuator/Security/JDBC | 运行时；HTTP、校验、健康、安全和参数化 SQL | Apache-2.0；Spring 官方维护，统一由 Boot 3.5.16 BOM 管理 | 手工 Servlet/JDBC 会增加装配与错误面；WebFlux 对 M2 无异步收益 |
| Spring Modulith 1.4.13 | 测试为主；模块模型与依赖验证 | Apache-2.0；Spring 官方稳定 1.4 线 | 仅 ArchUnit 可实现规则，但缺少统一的应用模块模型；M2 两者配合 |
| Flyway 11.7.2 + PostgreSQL module | 运行时；append-only Schema 迁移 | Apache-2.0；Boot BOM 管理 | Liquibase 功能更宽但 M2 不需要 XML/YAML changelog；手工 SQL 无历史门禁 |
| PostgreSQL JDBC 42.7.11 | 运行时；唯一业务数据库驱动 | PostgreSQL/BSD-style 2-clause；活跃维护，Boot BOM 管理 | H2 语义与 PostgreSQL 不一致，只允许纯领域单测，不用于迁移/API 集成 |
| PostgreSQL 17.11 | 本地/CI 运行时；业务事实数据库 | PostgreSQL License；17.x 官方维护中 | 18.x 支持期更长，但 M2 先选择成熟的 17.x；升级需要迁移和驱动兼容验证 |
| Testcontainers PostgreSQL 1.21.4 | 测试；真实空库迁移和 API 集成 | MIT；活跃维护，Boot BOM 管理 | 外部固定数据库降低隔离性；2.x 有迁移成本，待 Boot 兼容线升级再评审 |
| JUnit 5 / AssertJ / MockMvc / Spring Security Test / ArchUnit | 测试；领域、应用、API、安全和架构验证 | JUnit 为 EPL-2.0，AssertJ/ArchUnit/Spring 为 Apache-2.0；版本由 Boot/Modulith BOM 管理 | 自建测试框架没有收益 |

许可证依据来自各项目官方仓库：[Spring Boot](https://github.com/spring-projects/spring-boot/blob/main/LICENSE.txt)、[Spring Modulith](https://github.com/spring-projects/spring-modulith/blob/main/LICENSE)、[Flyway](https://github.com/flyway/flyway/blob/main/LICENSE.md)、[pgjdbc](https://github.com/pgjdbc/pgjdbc/blob/master/LICENSE)、[Testcontainers](https://github.com/testcontainers/testcontainers-java/blob/main/LICENSE)。实现 PR 必须附解析后的依赖树和许可证扫描结果；若传递依赖出现不兼容许可证则阻止合并。

明确不引入 Lombok、MapStruct、JPA、Redis、Kafka、OpenAPI UI、OpenTelemetry SDK 或模型 SDK。静态 OpenAPI 文件避免为两条 API 增加运行时文档端点；若后续漂移成本超过收益，再以依赖评审引入生成/校验工具。

## 测试策略

| 层级 | 必须覆盖 | 证据 |
|---|---|---|
| Domain unit | key/name 正反边界、NFC、控制字符、稳定 ID/时间注入、角色语义 | Surefire 单元测试 |
| Application unit | 创建成功、重复冲突映射、事务失败、审计失败、成员/非成员查询 | mock port 的用例测试，不启动 Web/DB |
| Architecture | Modulith verify；project 只依赖 identity/audit 公共 API；domain 禁止 Spring Web/JDBC/Jackson/driver/SDK；禁止字段注入 | `mvn verify` 架构测试 |
| Migration integration | PostgreSQL 17.11 空库 V1 迁移、二次迁移无变化、表/约束/schema 所有权 | Testcontainers |
| API integration | 401、无创建权限 403、合法创建 201、重复 409、成员 200、非成员与不存在同为 404、验证错误结构、traceId 一致 | MockMvc + PostgreSQL Testcontainer + 两个 actor |
| Concurrency | 两个并发请求创建同 key，仅一个成功且无孤立 membership/audit | PostgreSQL 集成测试 |
| Health/config | liveness 不依赖 DB；readiness 随 DB；不暴露敏感 Actuator；local 缺凭据启动失败 | context/集成测试 |
| Contract/logging | OpenAPI 路径/字段/状态与响应一致；日志/错误不含凭据、请求体、SQL/堆栈，traceId 可关联 | 契约 fixture + 日志捕获 |

验证顺序：最小领域/应用测试 → 架构测试 → PostgreSQL/API 集成测试 → `./mvnw verify` → 本地启动和 health/Project smoke test → 依赖/许可证/Secret 扫描。未实际运行的检查必须在 PR 中标记，任何权限负例或迁移失败均阻止 M2 退出。

M2 只可把 `AC-AUTH-001` 中 Project 创建/读取部分和 `AC-AUTH-002` 中 Project 行标为有证据；修改与其他资源矩阵仍为计划。`AC-AUDIT-001`、`AC-OBS-001` 也只能记录 M2 已覆盖的 Project 子集，不能提前宣称完整场景通过。

## 兼容、迁移和发布顺序

1. 合并本设计并保持 Issue #7 为跟踪入口。
2. 在同一 M2 分支按 0.5–2 天切片实现构建/Actuator、模块测试、数据库迁移、Project 应用、API/安全/日志。
3. 先在空 PostgreSQL 17.11 验证，再验证重复迁移与 API；数据库迁移文件合并后不可修改。
4. M2 PR 只发布一个 Platform 制品，不修改 Scanner 契约，也不要求其他仓库同步版本。
5. M3 从 M2 合并后的最新 Platform `main` 开始，复用 Project 公共应用边界，并新增而非改写 V1 迁移。

当前没有对外已发布 Platform 或数据库，M2 不承担历史数据迁移。Java 21 是 Platform 构建/运行下限；Scanner 的 Java 输入支持仍由 D14/M4 独立约束。

## 回滚方案

- 设计未实现前：撤销本分支文档提交即可，不影响数据库或其他仓库。
- 实现合并但尚未部署：回退 Platform 制品/提交；保留已合并迁移历史，不重写 V1。
- V1 已在环境执行：先停止写流量并回退应用；如 V1 结构本身造成问题，用新的前向补偿迁移修正。禁止自动 drop schema/table 的 down migration。
- `project` 创建事务原子化，失败不会留下 Project、成员或成功审计的部分状态。若发现跨模块耦合，先阻止 M3，再通过公开 API/新迁移修正，不拆微服务规避问题。

## 替代方案与取舍

| 方案 | 结论 | 原因 |
|---|---|---|
| Maven 多 module | M2 不采用 | 增加发布与构建结构，但对三个小模块的边界强度没有超过 Spring Modulith + ArchUnit；仍可在证据出现后重构且保持单制品 |
| 为全部候选模块生成空层 | 不采用 | 空 controller/service/repository 制造虚假完成感，违反按真实职责物化原则 |
| JPA/Hibernate | 不采用 | M2 查询简单，显式 SQL 更容易证明表所有权和避免持久化实体泄漏；未来复杂聚合可另评审 |
| H2 集成测试 | 不采用 | 不能可信验证 PostgreSQL schema、SQLSTATE、jsonb、约束和 Flyway 行为 |
| Testcontainers 2.x | 暂不采用 | 需要模块/包迁移且不在 Boot 3.5.16 管理线；替换成本主要在测试 import/artifact 和容器声明 |
| 客户端 actor header | 不采用 | 可伪造，无法满足 `V1-NFR-003`；测试便利不能削弱生产默认安全 |
| 跨模块外键到 audit | 不采用 | 让 audit 依赖 Project 表生命周期；审计改存稳定资源 ID 并保持自身可追踪性 |
| 异步审计/outbox | M2 不采用 | 同库同步事务已满足一致性；异步化会新增投递、重放、顺序和监控问题，尚无吞吐证据 |
| Redis/Kafka/微服务 | 不采用 | M2 无缓存、队列或独立扩缩需求，且与 ADR-0004/0005 相悖 |

## 未解决问题

| 问题 | 是否阻断 M2 | Owner / 最迟阶段 |
|---|---:|---|
| 正式 Identity/OIDC 提供方、组织和成员管理流程 | 否；M2 默认失败关闭并保留可替换身份端口 | M3 或独立 Identity ADR，生产开放前 |
| Project key 将来按组织唯一还是继续全局唯一 | 否；M2 无组织模型，key 不作为安全边界 | 组织模型设计前；若改变使用新增迁移和 API 兼容方案 |
| 审计查询 API、保留期、归档和删除策略 | 否；M2 只写最小记录 | M3/M11，对外发布前 |
| W3C trace 跨 Platform–Scanner 传播 | 否；M2 只有请求关联 ID | M5 集成设计 |
| PostgreSQL 迁移/运行账户分离和网络策略 | 否；本地/CI 可单账户 | M11 Deploy 设计与演练 |
| 性能/连接池/容量数值 | 否；当前无可信负载基线 | M12 基线接受后 |

上述问题均不改变 M2 的模块、授权、数据所有权或 API 主路径，因此不阻断实现；若正式身份方案要求信任客户端自报身份、跨 Project 共享表或放宽默认拒绝，则必须重新评审本设计。

## 设计审阅

2026-09-09 已将本文与 Issue #7、D14–D16、ADR-0004/0005 和 M2 阶段提示词交叉核对，并执行需求映射、链接、空白与高置信敏感信息检查。

审阅中发现并解决：

- 创建权限由“任意已认证主体”收紧为可信身份加应用层 `project:create` 双重检查。
- 角色直接采用 D14 的 `MAINTAINER/VIEWER` 语义，不引入未定义的 OWNER 层次。
- 成功审计与创建同事务；冲突/拒绝审计移到回滚后的独立短事务，避免失败记录随业务事务一同丢失。
- 无持久事实的 identity 不创建空 schema；OpenAPI 移出静态 Web 资源目录。
- Spring Security 的前置 `401/403` 纳入统一错误结构，不留下协议旁路。

最终结论：

- M2 范围和非目标没有把 Scan/Analyzer 能力提前带入。
- 认证默认失败关闭，Project 资源授权在应用查询和数据库条件中均可验证。
- Project、Identity、Audit 模块职责与表所有权闭合，无跨模块内部类/表访问。
- 依赖版本、许可证、替代方案和升级风险有明确依据。
- API、迁移、失败语义、traceId、审计和测试证据足以指导实现，没有阻断级待决项。
- M2 证据声明只覆盖 D15 场景的 Project 子集，不冒充 V1 已支持。

因此本设计评审为 **Accepted**，允许按 Issue #7 的小切片开始实现。实现 PR 必须以真实的 Maven、PostgreSQL、权限和架构测试结果再次验证这些假设；测试未通过时不得以本设计的 Accepted 状态代替运行证据。
