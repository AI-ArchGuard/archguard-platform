# 从 Project 模块读懂 Platform

本文帮助你结合中文代码注释阅读当前实现，不要求先熟悉整个 Spring 项目。第一批范围是 Project 的43个生产文件和2个专属测试；其他模块仍在后续批次，不代表整个项目已完成注释。

## 建议先读哪些文件

1. [ProjectController](../../src/main/java/io/github/aiarchguard/platform/project/web/ProjectController.java)：先看有哪些 HTTP 操作、参数从哪里来、响应如何返回，暂时不要深入数据库。
2. [ProjectOperations](../../src/main/java/io/github/aiarchguard/platform/project/ProjectOperations.java)：这是调用项目用例的公开接口，描述模块对外能做什么；接口与实现分开，调用方不必依赖模块内部类。
3. [ProjectApplicationService](../../src/main/java/io/github/aiarchguard/platform/project/internal/application/ProjectApplicationService.java)：看当前身份、参数校验、事务服务、失败审计和视图转换如何组成用例。
4. [TransactionalProjectCreator](../../src/main/java/io/github/aiarchguard/platform/project/internal/application/TransactionalProjectCreator.java)、[TransactionalProjectReader](../../src/main/java/io/github/aiarchguard/platform/project/internal/application/TransactionalProjectReader.java)、[TransactionalProjectManager](../../src/main/java/io/github/aiarchguard/platform/project/internal/application/TransactionalProjectManager.java)：理解真正的事务边界、查询过滤、行锁与版本检查。
5. [Project](../../src/main/java/io/github/aiarchguard/platform/project/internal/domain/Project.java)、[ProjectKey](../../src/main/java/io/github/aiarchguard/platform/project/internal/domain/ProjectKey.java)、[ProjectName](../../src/main/java/io/github/aiarchguard/platform/project/internal/domain/ProjectName.java)：理解领域对象如何表达一个合法项目。领域层不需要知道 HTTP、Spring JDBC 或数据库行结构。
6. [ProjectRepository](../../src/main/java/io/github/aiarchguard/platform/project/internal/application/ProjectRepository.java)与[JdbcProjectRepository](../../src/main/java/io/github/aiarchguard/platform/project/persistence/JdbcProjectRepository.java)：前者定义应用所需的数据访问能力，后者用参数化 SQL 实现；这是“端口”和“适配器”的一个具体例子。
7. [ProjectApiIntegrationTest](../../src/test/java/io/github/aiarchguard/platform/ProjectApiIntegrationTest.java)和[ProjectValueObjectsTest](../../src/test/java/io/github/aiarchguard/platform/project/internal/domain/ProjectValueObjectsTest.java)：用场景反过来检查自己是否理解权限、并发、审计和名称规范化。

## 一次创建请求经历什么

Controller 接收 key/name，先执行 HTTP 输入校验，再调用 `ProjectOperations.create`。应用服务从可信身份提供器取得当前调用者，检查窄权限 `project:create`，构造领域对象并调用创建事务服务。

创建事务服务把项目、创建者的维护者成员关系以及成功审计放在同一事务中。JDBC 适配器负责写入 `project.projects` 和 `project.project_members`；审计通过公开 `AuditRecorder` 接口交给审计模块，而不是由 Project 直接写其他模块的表。

事务成功返回后，应用视图转换为 HTTP 响应。客户端得到 `201` 和新资源的 `Location`；创建者身份和维护者角色不是由请求正文自行指定。

如果 key 与已有记录冲突，数据库唯一约束也能处理两个请求同时创建的竞争。事务调用抛出重复键异常后，应用服务将其转换为稳定的项目冲突异常，并尝试记录失败审计。异常处理器最后返回 `409`，不会泄露占用该 key 的项目或成员信息。

## 身份、角色与“项目不存在”

调用者身份、目标成员 ID 和项目 ID 是不同概念。成员管理路径里的 `actorId` 是被修改的成员，不是允许客户端自报自己的身份。

Project 内只有 `MAINTAINER` 和 `VIEWER` 两种角色。成员可以读取自己可见的项目；维护者可以修改项目和管理成员。创建权限 `project:create` 是身份携带的应用权限，不等同于某个已有项目的成员角色。

项目查询 SQL 同时约束项目 ID 和调用者 ID。没有成员关系和项目本身不存在都会得到相同的项目 `404`，避免被用来探测其他人的项目。已可见但只有查看者角色的写操作则返回 `403`。

其他模块通过 [ProjectAuthorization](../../src/main/java/io/github/aiarchguard/platform/project/ProjectAuthorization.java)申请项目级授权。`requireViewerForActor` 中的 actor UUID 必须由可信调用方取得；该方法查询成员资格，并不会替调用方认证一个任意传入的 UUID。

## 两类并发保护为什么都存在

修改名称和删除请求携带“我上次读取到的 version”。服务端读取当前状态，并在实际 SQL 的 `WHERE` 条件中再次约束期望版本；修改成功后版本加一。版本不匹配时返回冲突，不静默覆盖别人已保存的内容。

成员增改删先锁定项目行，使使用同一锁定路径的成员命令按项目串行执行。降级或移除维护者前检查维护者数量，防止最后一名维护者被移除。这个数量检查必须结合事务和同一项目行锁理解，不能只把它当成普通的“先查后改”。

## 删除为什么还要询问其他模块

[ProjectDeletionGuard](../../src/main/java/io/github/aiarchguard/platform/project/ProjectDeletionGuard.java)是模块提供的删除保护扩展点。应用服务在删除前逐项询问相关模块是否还存在内容；Repository、RuleSet、ScanJob、文档和 Agent 的实现分别负责自身数据，不由 Project 跨模块查表。

这些内容检查发生在调用删除事务服务之前，不能把它解释为覆盖全部模块的全局原子删除事务。随后删除事务仍会检查当前维护权限、期望版本和实际删除结果。Project 的成员关系由数据库外键级联处理，审计历史继续保留；它不是“删除整个项目的一切历史”的接口。

## 成功审计与失败审计并不相同

成功创建、修改、删除或成员变更的审计调用位于对应事务服务中，审计写入失败会使该成功路径不能正常提交。

应用服务捕获冲突或权限异常后调用 `recordFailure`，是在通常的事务代理返回/回滚路径之外尝试写失败审计。不要把这段代码误读为它声明了独立的 `REQUIRES_NEW` 事务：这里没有该注解。若失败审计自身再次失败，代码只记录受控错误日志并保留原先的业务拒绝，不把拒绝变成放行。

## 领域对象、应用视图与 HTTP DTO 为何分开

- `internal/domain` 的值对象处理项目本身的不变量，例如稳定 key、名称规范化和有效标识。
- 模块根包的 `ProjectView`、`ProjectPage` 等是对外应用接口返回的快照，不把模块内部实现暴露给调用方。
- `web` 下的请求和响应 record 属于 HTTP 协议边界；它们只映射所需字段，不是数据库实体。
- `internal/application` 的分页数据与 `ProjectAccess` 是模块内部协作数据，不作为跨模块接口使用。

这种分离让 HTTP、应用编排与数据库实现各有职责，也使其他模块只能依赖 Project 的公开边界。

## 如何用测试辅助理解

先运行值对象单测，观察 key 校验、空格处理、NFC 名称规范化和控制字符拒绝。再读 API 集成测试，逐个对照创建权限、非成员隐藏、分页隔离、重复 key、并发创建、版本冲突和最后维护者保护。

API 集成测试使用独立的 PostgreSQL Testcontainers 数据库和测试安全主体，不使用本机部署账号，也不表示生产 OIDC 校验可以省略。它们验证的是已有行为，不是本次新增的业务功能；所有输入、断言和 SQL 保持原样。

已发布的 SQL 迁移、自动生成的声明和固定扫描输入没有为了加注释而改写。它们可能被校验和、行号或报告摘要引用，需要通过阅读说明理解，而不能把“只加注释”当作任意修改这些字节的许可。
