# Platform 中文代码注释分批实施计划

> **For agentic workers:** Use superpowers:executing-plans with bounded parallel annotation work and one final whole-change review. Steps use checkbox syntax. Preserve this plan's local evidence and all other worktrees.

**Goal:** 分批用中文注释和阅读指南解释 Project 及接续的 Platform 模块，不改变行为。

**Architecture:** 复用现有 Controller、公开应用边界、应用/事务服务、领域对象和 JDBC 适配器，仅补注释。以实际 main `35e721bb5e612e505ae7a9a3e4b391ea64f1352e` 为基线，原用户检出不动。

**Tech Stack:** Java21、现有 Maven Wrapper/Spring/JUnit；验证工具只使用 JDK 自带编译器，无新增依赖。

**Spec:** [已批准的中文注释约定](../specs/2026-10-24-chinese-code-comments.md)。

## Global Constraints

- 只改注释/说明；Java可执行语法、字面量、接口及测试断言不变。
- 16个已发布迁移、生成文件、fixture/黄金数据、契约、依赖、CI、配置值及许可证不变。
- 用户修改、其他分支、运行环境、凭据和历史不动；不部署或调用模型。
- 本批不代表全部模块/仓库完成，不自动推送、建PR或合并。

## Review Focus

- 注释不得声称锁、审计或删除保护提供了实现中不存在的原子性/保证。
- 显式 actor 授权入口不能被解释为允许客户端自报身份；404隐藏与403权限不足须区分。
- 成功路径事务和失败审计边界须按当前代码解释，不照抄过期设计。
- 修改仅在注释，尤其不能改 Java文本块、SQL、测试字符串及工具指令。
- 小型 DTO/异常也应有实际业务解释；阅读指南须链接真实文件并标明剩余覆盖范围。

## Task 1: 基线与保行为证据

**Files:** 本计划/规范；忽略目录中的验证工具和日志，不进入生产代码。

**Interfaces:** 输入 base35e721；输出既有测试结果和修改前后 Java AST 比较器，供 Task2/3验证。

- [x] 核对独立工作树、干净状态、main身份；阅读 README/AGENTS、Project设计和代码。
- [x] 运行 ProjectValueObjectsTest、ProjectApiIntegrationTest 基线，保存输出及跳过项。
- [x] 建立仅本地的 JDK AST 比较器；用“只加注释通过、改常量/条件/签名失败”的独立小样例验证工具本身。
- [x] 记录当前顶层类型中文说明缺口，作为文档覆盖清单，不新增业务测试或伪造行为失败。

## Task 2: Project 全链路中文注释

**Files:** 以下均为修改现有 Java，只加注释。

`src/main/java/io/github/aiarchguard/platform/project/`：

- `InvalidProjectException.java`、`LastProjectMaintainerException.java`
- `ProjectAccessView.java`、`ProjectAuthorization.java`、`ProjectDeletionGuard.java`
- `ProjectKeyConflictException.java`、`ProjectMemberNotFoundException.java`
- `ProjectMemberPage.java`、`ProjectMemberRole.java`、`ProjectMemberView.java`
- `ProjectNotEmptyException.java`、`ProjectNotFoundException.java`、`ProjectOperations.java`
- `ProjectPage.java`、`ProjectPermissionDeniedException.java`、`ProjectVersionConflictException.java`、`ProjectView.java`

其子目录：

- `internal/domain/Project.java`、`ProjectId.java`、`ProjectKey.java`、`ProjectMember.java`、`ProjectName.java`、`ProjectRole.java`
- `internal/application/ProjectAccess.java`、`ProjectApplicationService.java`、`ProjectIdGenerator.java`、`ProjectMemberPageData.java`、`ProjectPageData.java`、`ProjectRepository.java`、`TransactionalProjectCreator.java`、`TransactionalProjectManager.java`、`TransactionalProjectReader.java`
- `infrastructure/RandomProjectIdGenerator.java`
- `persistence/JdbcProjectRepository.java`
- `web/CreateProjectRequest.java`、`ProjectController.java`、`ProjectExceptionHandler.java`、`ProjectListResponse.java`、`ProjectMemberListResponse.java`、`ProjectMemberResponse.java`、`ProjectResponse.java`、`SetProjectMemberRequest.java`、`UpdateProjectRequest.java`

测试：

- `src/test/java/io/github/aiarchguard/platform/ProjectApiIntegrationTest.java`
- `src/test/java/io/github/aiarchguard/platform/project/internal/domain/ProjectValueObjectsTest.java`

**Interfaces:** 原有方法签名、调用、事务与返回值全部保持；分工只按互不重叠文件集合并行，不跨模块修改。

- [x] 为43个生产/2个测试顶层类型补中文 Javadoc；主要方法和关键分支补实际业务解释。
- [x] 保留字符串、SQL、注解、测试fixture与断言；所有新注释避免真实凭据或数据。
- [x] 对45个 Java 文件逐一比较基线/当前AST；核对其他源码与资源未修改，中文类型说明覆盖45/45。
- [x] 最小相关测试及完整 `mvnw --batch-mode --no-transfer-progress verify`；不得修改测试或降低断言以通过。

## Task 3: 阅读入口与整体验证

**Files:** 新建 `docs/code-reading/project-module.zh-CN.md`；修改 `README.md` 仅加阅读入口。

**Interfaces:** 解释 Task2 的实际调用关系；链接源码，不复制业务实现或变更契约。

- [x] 交代入口/公开边界/事务/领域/数据库/测试的推荐阅读顺序，解释创建、读取、成员管理、删除与失败审计的差异。
- [x] 明确中文注释已覆盖 Project，本任务不声称其他模块完成。
- [x] 检查所有相对链接、空白和允许的文件集合；一次独立评审，修正高置信注释错误。
- [x] 保存本地可审阅结果和真实验证记录，不推送/建PR/合并/部署。回滚只需撤回本地注释/说明提交，不涉及应用或数据库回滚。

## 第一批实际验证（2026-10-24）

- 基线和修改后 Project 相关测试均18项通过，0失败/错误/跳过。
- 45个 Java 文件与精确基线的可执行AST一致，45个顶层类型都有中文说明；原Java行无删除/替换。
- 完整 Maven verify 成功：942项、939通过、0失败/错误、3项因 Windows 条件跳过。跳过项为 ControlledSyntheticBatchInventoryTest 的 rejectsSymbolicLinksForBothFileAndParent、EncryptedCredentialConsumptionTest 的 symlinkMasterOrCiphertextNeverPermitsConsumption，以及 EncryptedCredentialStoreTest 的 symlinksCannotSelectMasterOrCiphertext；本批未声称执行这些 POSIX 路径测试。
- 61个本地阅读链接及空白/允许文件范围检查通过；一次独立整组评审，无 Critical/Important/Minor 发现。
- SQL迁移、生成文件、fixture/契约、依赖、CI、配置和许可证均未修改。没有推送、PR、部署或模型调用。

## 后续待办（本批不冒充完成）

- [x] Platform：Project、Repository、RuleSet、ScanJob、Finding、Governance及各批次列明的测试。
- [ ] Platform：Agent、文档/凭据、身份/通用/审计及尚未列明的测试（包括其余治理契约/迁移测试）。
- [ ] Scanner：生产与测试；固定 Java扫描输入用相邻说明解释。
- [ ] Web：手写源码与测试，生成声明保持原样。
- [ ] Deploy/Samples/Docs工具：脚本注释；运行配置及固定输入不改。

## 第二批：Repository / RuleSet（继续已批准的注释范围）

基线为第一批本地提交 `37d62e79d9fccbd4a5b99b7f3f676feb818fdb41`；沿用同一独立注释分支，保留 Tasks1–3 已完成记录，不重做第一批。仍只修改注释/说明，不推送、合并或部署。

### 第二批 Review Focus

- 本地路径注册不是 Git clone；路径检查不保证消除文件系统竞态或持续不变。
- 对外用例的项目授权与内部 Catalog 查询职责不能混淆；传入 UUID 本身不证明身份。
- Repository/RuleSet 的存储和审计没有整体事务声明，不套用 Project 的事务保证。
- 规则版本按实际原始 UTF-8 字节计算摘要，版本分配不声称自动重试或并发串行化。
- Scanner 子进程的环境过滤、输出处理、超时和清理只解释实际代码，不夸大为完整沙箱或全进程树回收。

## Task 4: 第二批基线与范围

**Files:** 本计划和已有通用规范；本计划忽略目录里的 AST 审计器与日志。

**Interfaces:** 消费第一批已完成的35e721→37d62e7记录；以37d62e7比较第二批，扩展累计审计到74个 Java 文件，不修改生产逻辑。

- [x] 核对独立工作树和干净状态，阅读 MVP 设计、当前 Repository/RuleSet 实现及相关测试。
- [x] 运行 ControlledRepositoryPathTest 和 GovernanceApiIntegrationTest 作为路径/实际消费链基线，记录真实结果，不新增行为测试。
- [x] 扩展已自测的 JDK AST 审计范围：保留第一批45文件，加本批28生产文件及1路径测试，先记录中文类型说明缺口。

## Task 5: Repository / RuleSet 中文注释

**Files:** `src/main/java/io/github/aiarchguard/platform/repository/` 的13文件：

- `InvalidRepositoryException.java`、`RepositoryCatalog.java`、`RepositoryConflictException.java`、`RepositoryNotFoundException.java`、`RepositoryOperations.java`、`RepositoryView.java`
- `internal/ControlledRepositoryPath.java`、`internal/RepositoryApplicationService.java`、`internal/RepositoryProjectDeletionGuard.java`、`internal/RepositoryStore.java`
- `persistence/JdbcRepositoryStore.java`、`web/RepositoryController.java`、`web/RepositoryExceptionHandler.java`

`src/main/java/io/github/aiarchguard/platform/ruleset/` 的15文件：

- `InvalidRuleSetException.java`、`RuleSetCatalog.java`、`RuleSetConflictException.java`、`RuleSetNotFoundException.java`、`RuleSetOperations.java`、`RuleSetVersionView.java`、`RuleSetView.java`
- `internal/RuleValidationPort.java`、`internal/RuleSetApplicationService.java`、`internal/RuleSetProjectDeletionGuard.java`、`internal/RuleSetStore.java`
- `infrastructure/ScannerProcessRuleValidator.java`、`persistence/JdbcRuleSetStore.java`、`web/RuleSetController.java`、`web/RuleSetExceptionHandler.java`

测试：`src/test/java/io/github/aiarchguard/platform/repository/internal/ControlledRepositoryPathTest.java`。

**Interfaces:** 保留现有所有 Java 接口、SQL、字符串、规则 YAML、断言及配置；可纠正不符合实际代码的旧自然语言注释，不改工具指令。

- [x] 每个顶层类型补中文 Javadoc；主要方法解释授权、路径规范化、版本与摘要、持久化和错误映射。
- [x] 路径测试解释正常化、绝对路径/遍历/缺失/文件拒绝的场景，不把未测试的链接或竞态说成已覆盖。
- [x] 累计74文件 AST 与37d62e7一致；本批29文件及累计74类型中文说明齐全，冻结资源无改动。
- [x] 运行最小相关测试及完整 Maven verify；如实披露平台跳过项，不改断言。

## Task 6: 第二批阅读指南与交付

**Files:** 新建 `docs/code-reading/repository-ruleset.zh-CN.md`；更新 README 阅读入口及通用规范的批次记录。

**Interfaces:** 通过真实源码链接解释 Task5，不复制契约或改动运行候选。

- [x] 说明从 Project 到仓库注册、规则集、规则版本与 Scanner校验的阅读顺序；标明 Catalog/授权和事务/进程限制。
- [x] 检查链接、空白和允许文件范围；一次独立评审本批差异，不重复评审第一批。
- [x] 保存本地注释提交与真实验证记录；后续模块/仓库保持未完成，不推送或部署。

## 第二批实际验证（2026-10-24）

- 基线及修改后相关回归均6项通过，0失败/错误/跳过；不把消费链回归说成完整注册/CLI集成证明。
- 累计74个 Java 文件与37d62e7的可执行AST一致，74个顶层类型均有中文说明；第一批源文件保持原样。
- 完整 Maven verify 成功：942项、939通过、0失败/错误、3项 Windows-disabled 符号链接测试跳过（名称同第一批记录），本批未声称运行这些 POSIX 路径。
- 80个本地阅读链接、空白和允许文件范围检查通过；本批一次独立评审无 Critical/Important/Minor 发现。
- 仅纠正一条无对应实现的旧 janitor 重试说明，没有实现新清理逻辑；SQL、YAML、接口、断言、配置、契约/fixture、依赖和CI不变。

## Task 7: 第三批 ScanJob／Finding 基线（2026-10-25）

**Files:** 本计划、既有规范；沿用忽略目录中的 AST 工具和测试日志。

**Interfaces:** 输入第二批提交 `a83536db93d869b7579c72c35d95d6c83136c5d7`；输出供 Task8/9 使用的累计116文件清单和既有测试基线。

- [x] 核对隔离工作树及原用户修改，阅读 README、AGENTS、MVP 需求/设计与 ADR-0004/0005/0006。
- [x] 运行 `ResultReportConsumerTest,GovernanceApiIntegrationTest` 相关基线；预期7项通过，不把它们说成真实 Runner 端到端证明。
- [x] 扩充本地 AST 清单至116文件并自测；预期修改前116文件可执行AST一致，已有中文说明覆盖74个。

## Task 8: 扫描调度、结果接受与处置中文注释

**Files:** `src/main/java/io/github/aiarchguard/platform/scanjob/` 全部20个 Java 文件；`src/main/java/io/github/aiarchguard/platform/finding/` 全部21个 Java 文件；`src/test/java/io/github/aiarchguard/platform/finding/internal/ResultReportConsumerTest.java`。

**Interfaces:** 保留所有签名、状态、SQL、字符串、配置和断言；两模块分别由互不重叠的注释协作者处理，根执行者拥有测试和阅读文档。

- [x] 为41个生产类型和1个测试类型补中文职责说明，并解释主要方法和关键分支。
- [x] 按实际代码区分幂等键/请求摘要、lease/attempt token、取消请求/进程退出、执行状态/规则结论、报告Schema/语义校验、处置/确定性Finding。
- [x] 不把数据库单条原子更新、Finding批量写入和任务完成误写成跨模块全局事务；说明邮箱原子移动的降级与尽力清理边界。
- [x] 累计116文件 AST 保持一致、116个顶层类型有中文说明，前两批74文件不动；先运行相关7项测试，再执行完整 Maven verify。

## Task 9: 第三批阅读指南、评审与本地交付

**Files:** 新建 `docs/code-reading/scanjob-finding.zh-CN.md`，更新 README 阅读入口及规范批次记录。

**Interfaces:** 从提交扫描到邮箱调度、结果校验/存储、查询与人工处置的真实源码导航，消费 Task8 的只注释差异。

- [x] 补阅读顺序和实际限制，检查所有本地源码链接、空白及允许范围。
- [x] 对本批差异作一次独立评审，重点检查取消、重试、固定版本、原始报告/规范化数据和事务/授权的描述。
- [x] 保存本地提交并记录实际测试和跳过项；全项目剩余覆盖保持待办，不推送、部署或调用模型。

## 第三批实际验证（2026-10-25）

- 首次相关基线中报告消费3项通过，集成4项因 Docker 未运行而初始化失败；用户启动 Docker 后重跑7项全部通过，修改后7项亦全部通过，0失败/错误/跳过。未调整测试或配置来绕过失败。
- 累计116个 Java 文件与 `a83536d` 可执行 AST 一致，116个顶层类型有中文说明；前两批74文件及本批范围外资源未改变。
- 完整 `mvnw --batch-mode --no-transfer-progress verify` 成功：942项、939通过、0失败/错误、3项既有 Windows-disabled 符号链接测试跳过；93份 JUnit XML 汇总一致。跳过项仍为 `ControlledSyntheticBatchInventoryTest.rejectsSymbolicLinksForBothFileAndParent`、`EncryptedCredentialConsumptionTest.symlinkMasterOrCiphertextNeverPermitsConsumption`、`EncryptedCredentialStoreTest.symlinksCannotSelectMasterOrCiphertext`；本批不声称运行了这些路径。
- 允许范围为42个 Java 文件和4份说明，共46文件；114个本地阅读链接及空白检查通过。一次独立评审无 Critical/Important，指出1处报告大小下限的单位歧义，已仅将注释修正为“1 字节到 50 MiB”；未改阈值或逻辑。修正后再次确认116文件 AST 一致、相关7项通过，最终完整检查亦成功，仍为942项中939通过、3项 Windows 条件跳过，93份 XML 汇总一致。本批以本地提交交付，没有推送、部署或模型调用。

## Task 10: 第四批 Governance 基线（2026-10-26）

**Files:** 本计划、既有注释规范；忽略目录中的 AST 工具与验证日志。

**Interfaces:** 以第三批提交 `72262f57b3104c6bd3702253b1faed4695113f66` 为基线，沿用隔离工作树；为 Task11/12 提供累计204文件保行为证据。

- [x] 核对工作树、已完成批次和原用户修改；阅读 Governance 需求、3B–3G 设计、ADR-0008/0009 及实际代码。
- [x] 运行 `FindingClassifierTest,GatePolicyTest,ReportFingerprintComputerTest,SubmissionContractTest,GovernanceApiIntegrationTest,GovernanceCiApiIntegrationTest` 基线；预期20项通过，0失败/错误/跳过。
- [x] 扩展 AST 工具至204个文件，自测并记录初始中文说明数量；可执行结构预期全部相同。

## Task 11: Governance 全链路中文注释

**Files:** `src/main/java/io/github/aiarchguard/platform/governance/` 全部82个生产 Java 文件（根目录42、internal24、persistence7、web9）；`src/test/java/io/github/aiarchguard/platform/governance/internal/` 全部4个测试及 `GovernanceApiIntegrationTest.java`、`GovernanceCiApiIntegrationTest.java`。

**Interfaces:** 公共类型及 Web、内部算法/应用服务、JDBC 存储按不重叠集合分工；根执行者拥有6个测试与阅读文档。签名、注解、配置、SQL、字面量、契约、fixture 和断言不变。

- [x] 为82个生产类型和6个测试类型补中文职责说明，解释主要方法、调用关系及关键分支。
- [x] 明确比较空间、逻辑指纹/扫描ID、基线版本/活动选择、三类集合差、例外有效区间、门禁与CI退出码；不把人工处置写成门禁例外。
- [x] 按实际事务解释报告导入、门禁求值及完成关联的分段恢复；区别幂等与恰好一次、提交状态与异步执行。
- [x] 按代码解释 Webhook 签名/时间窗/head顺序、PR修订差异与基线比较、只读视图、MVC失败审计覆盖；不推导Git ancestry或公网验收。
- [x] 累计204文件 AST 一致且顶层中文说明齐全，原116文件不动；相关20项及完整 Maven verify 通过，跳过项如实记录。

## Task 12: Governance 阅读指南与本地交付

**Files:** 新建 `docs/code-reading/governance.zh-CN.md`；更新 README、注释规范及本计划。

**Interfaces:** 用实际源码导航串联 Task11，讲解从成功报告到基线、比较、门禁及CI/Web消费的路径，不复制或修改机器契约。

- [x] 完成阅读顺序、现有边界和测试意图；本地链接、允许范围及空白检查通过。
- [x] 一次独立评审本批差异，核对不可变/事务/并发、身份/来源与状态及测试覆盖描述；准确性问题在只注释范围内修正。
- [x] 保存本地提交和真实验证记录；其他模块、剩余契约测试和其他仓库保持待办，不推送、合并、部署或调用模型。

## 第四批实际验证（2026-10-26）

- 相关6个测试类共20项，基线和修改后均通过，0失败/错误/跳过；涉及真实测试 PostgreSQL 与本地 MockMvc，不代表真实 GitHub/CI 投递。
- 累计204个 Java 文件与 `72262f5` 可执行 AST 一致，204个顶层类型有中文说明；原116文件及范围外资源未改变。
- 本批范围为88个 Java 文件和4份说明，共92文件；154个本地阅读链接和空白检查通过。Java 仅替换3处原英文自然语言说明，其余为新增注释；SQL、字符串、注解、断言及固定数据不变。
- 一次独立评审无 Critical/Important/Minor 发现，另核对新指南39个本地链接；没有待修文案。
- 本批完整 `mvnw --batch-mode --no-transfer-progress verify` 成功：942项、939通过、0失败/错误、3项既有 Windows 11 条件跳过，93份 JUnit XML 汇总一致。跳过的3个符号链接测试与第三批记录同名，未声称在本机执行这些路径。本批以本地提交交付，不推送、合并、部署或调用模型。
