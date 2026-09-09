# Technical Design：OIDC 与 Project 生命周期/成员管理

- Issue：[archguard-platform#7](https://github.com/AI-ArchGuard/archguard-platform/issues/7)
- 状态：Proposed（随实现 PR 评审）
- 需求：`V1-FR-001`、`V1-FR-012`、`V1-NFR-003`、`V1-NFR-006`
- 前置设计：[M2 Platform 骨架与 Project 最小垂直切片](m2-platform-skeleton-and-project-slice.md)

## 问题与范围

M2 首个切片只提供默认失败关闭的身份端口，以及 Project 创建/单项查询。继续交付 V1 控制面需要可信生产身份入口、Project 列表/修改/删除和显式成员治理，同时保持模块化单体、表所有权、统一错误和审计边界。

本设计只扩展 `identity` 与 `project` 模块，不引入组织、用户目录、邀请邮件、Repository/Policy/Scan，也不拆分服务。成员目标由 OIDC `sub` 的 UUID 指定；显示名和搜索必须等 Identity 目录需求明确后另行设计。

## Identity/OIDC 适配器

Platform 作为 OAuth 2.0 Resource Server 直接校验 OIDC JWT，不信任反向代理身份头、Cookie、自报 actor 或仅解码未验签的 token。生产启用 `oidc` profile，并配置：

| 配置 | 约束 | 用途 |
|---|---|---|
| `ARCHGUARD_OIDC_ISSUER_URI` | 必填、绝对 HTTPS URI | 绑定唯一可信 issuer，并验证 `iss`、时间声明和签名密钥 |
| `ARCHGUARD_OIDC_AUDIENCE` | 必填、非空 | 要求 `aud` 包含 Platform 的资源标识，阻止其他 API 的 token 被重用 |
| `ARCHGUARD_OIDC_JWK_SET_URI` | 可选、绝对 HTTPS URI | 显式指定 JWKS；省略时从 issuer 发现配置 |

Token 契约固定为：

- `sub` 必须是稳定 UUID，并成为 `CurrentActor.id`；email、用户名和显示名不作为资源主键。
- `scope` 或 `scp` 使用标准空格分隔权限，去除 Spring 默认前缀后映射到应用权限；创建 Project 需要 `project:create`。
- issuer、audience、时间声明、UUID subject 或签名任一失败均返回统一 `401 authentication.required`，不回传验签细节。
- 会话为 stateless，关闭 form login、HTTP Basic、Cookie session、logout 和 CSRF；只允许 health 路径匿名访问。
- 未启用 `oidc` profile 时保留原来的失败关闭链，不提供临时生产身份。

省略 JWKS URI时，启动需要 Identity Provider 的 discovery 可用；显式 JWKS 时仍校验 issuer，但密钥只从配置的 HTTPS 地址获取。Identity Provider 不可用、密钥轮换失败或配置缺失均不得降级为匿名或自报身份。

## Project API 与权限矩阵

| 动作 | `MAINTAINER` | `VIEWER` | 非成员 |
|---|---:|---:|---:|
| 获取/分页列出 Project | 允许 | 允许 | 列表不出现；单项返回 404 |
| 查看成员列表 | 允许 | 允许 | 404，隐藏 Project 是否存在 |
| 修改名称、删除 Project | 允许 | 403 | 404 |
| 新增、改角色、移除成员 | 允许 | 403 | 404 |

API 扩展如下：

- `GET /api/v1/projects?page=0&size=20`：只返回当前 actor 的成员 Project，按 `createdAt DESC, id DESC` 稳定排序；`size` 为 1–100。
- `PATCH /api/v1/projects/{projectId}`：请求含 `name` 与期望 `version`；Project key 继续不可变。
- `DELETE /api/v1/projects/{projectId}?version=n`：版本匹配时删除 Project 和级联成员，保留独立 audit 记录。
- `GET /api/v1/projects/{projectId}/members?page=0&size=20`：Project 成员可读。
- `PUT /api/v1/projects/{projectId}/members/{actorId}`：幂等新增成员或替换 `MAINTAINER/VIEWER` 角色。
- `DELETE /api/v1/projects/{projectId}/members/{actorId}`：删除成员。

分页采用零基 offset page，响应固定包含 `items/page/size/total`。这是当前控制面规模下的显式基线；大数据或高并发翻页出现证据后再评审 cursor，不提前引入第二种分页契约。

## 一致性、不变量与审计

现有 `project.projects.version` 用作乐观锁。名称修改成功后版本加一；修改和删除的旧版本返回 `409 project.version_conflict`，防止静默覆盖。成员变更锁定 Project 行，使同一 Project 的成员命令串行执行，并在降级或移除维护者前检查维护者数量；任何时刻至少保留一名 `MAINTAINER`，否则返回 `409 project.last_maintainer`。

成功的 Project 修改、删除和成员变更与对应 `project.update`、`project.delete`、`project.member.set`、`project.member.remove` 审计在同一事务提交。权限拒绝、版本冲突和成员不变量冲突在业务事务回滚后写最小失败审计；审计 metadata 只保留版本、目标 actor UUID 和角色等 allow-list 字段。

删除当前为物理删除，因为本阶段 Project 只有同 schema 的成员子表；外键负责级联成员，audit 没有跨模块外键并继续保留稳定 Project UUID。Repository 等真实子资源加入后，删除前置条件与保留策略必须在对应设计中扩展，不能依赖数据库意外级联。

## 数据库与兼容

本扩展不新增迁移：V1 已包含成员表、角色约束、actor 索引、Project `version` 和成员级联删除。已发布的 V1 文件保持不变。所有新增 API 位于既有 `/api/v1` 下；Project 响应新增 `version`，当前仍为未发布的 `0.1.0-SNAPSHOT`，在首个外部发布前冻结契约。

部署顺序为：先配置 Identity Provider 的 audience/scope/UUID subject，再部署带 `oidc` profile 的 Platform，最后开放 Project 流量。回滚时先停止写流量，再回退 Platform 制品；无数据库迁移需要回退，已写 audit 保留。若回退到旧制品，新增 API 消失但既有 Project/成员数据保持兼容。

## 依赖选择

新增 `spring-boot-starter-oauth2-resource-server`，版本由 Spring Boot 3.5.16 BOM 管理，许可证 Apache-2.0。它复用 Spring Security/Nimbus 的标准 Bearer、JWT、JWKS 和密钥轮换路径；替代方案是自写 JWT 校验（安全风险和维护面更大）或在代理终止身份后传 actor header（扩大信任边界且当前明确禁止），均不采用。不新增 Identity SDK，保持提供方中立。

## 测试与退出证据

- 单元：audience、UUID subject、HTTPS 配置、scope 权限映射。
- 安全集成：有效 Bearer token 进入 API；无效 token 使用统一 401。
- Project 集成：列表隔离、分页边界、维护者修改/删除、viewer 拒绝、版本冲突、成员增改删、最后维护者保护和审计保留。
- 架构：Modulith 边界与领域层框架独立继续通过。
- 全量：`mvnw verify`、`git diff --check`，CI 在 PR 上复跑。

已知限制：没有组织模型、用户搜索/邀请、token 撤销回调和细粒度 scope 管理 UI；这些不降低 JWT 验证与 Project 资源授权，必须在生产身份提供方选型和组织需求明确后单独设计。
