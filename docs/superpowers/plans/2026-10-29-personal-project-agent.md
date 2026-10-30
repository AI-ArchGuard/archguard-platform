# Personal Project Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在普通Finding/PR选择入口后交付独立日常授权、不可变最小快照和一次真实适配器执行路径；本计划只授权默认关闭、合成数据/假HTTP开发，不授予真实调用或费用。

**Architecture:** 保持Platform模块化单体，复用公开领域接口、`agent.requests`、严格Output/引用验证、固定官方适配器、凭据回调、原始期限和结果事务。日常风险接受/grant/设置/请求关联独立追加，与synthetic共享金额日账本；通过窄lease/association复用执行算法，不伪造batch/template。

**Tech Stack:** Java 21、现有Spring/Jackson/JdbcClient、PostgreSQL 17.11、Flyway、JUnit/AssertJ/Testcontainers及现有JSON Schema validator；不新增依赖。

**Spec:** [生产者设计](../../technical-design/v0.5-agent-personal-project-use.md)，依据本地Docs `da1b609` 的ADR-0016及FR-018–022。设计基线Platform `0bfb0af20c546404c499b7177e096cff9772f92f`，Agent最高V16；远端Docs合入与main CI须另外核对。

## Global Constraints

- 旧普通Agent API、enablement/settings 0.4.0、live 0.1.0、batch 0.3.0、Agent Output 0.1.0及Scanner契约保持原字节；新增Daily API 0.1.0，不能增改旧严格响应字段。
- `archguard.agent.daily.enabled=false`；没有默认grant、次数、累计金额或有效期。风险接受、grant、Project设置、部署开关分开留存；开发测试值不等于所有者批准。
- 仅配置owner可使用日常路径；解释需Viewer，摘要需Maintainer，控制写需owner+Maintainer。跨Project资源隐藏404，所有旧读取入口也不得旁路owner/资源授权。
- 完整编码JSON≤6,000 UTF-8字节、输出≤1,500 Token；输入8,000仅为调用后停发阈值。每attempt预留316,373 microUSD，单次/Project UTC日/部署UTC日技术上限350,000/5,000,000/20,000,000 microUSD，可下调，不是花费许可。
- 固定deepseek-flash、DeepSeek-V4.1-Flash-1017、responses-v1-restricted、deepseek-flash-context-peak-usd-2026-10-15和现有Prompt/Output；不更换协议、白名单、数据范围或Key接口，不新增工具/聊天/重试。
- 一个请求最多一个attempt；同grant同时一个活动逻辑请求。仅成功结果及可信费用都提交后释放逻辑槽；任何失败/UNKNOWN停止grant。次数永不退款，UNKNOWN不自动改写。
- 共享`agent.live_budget_usage`的纯金额；不使用fake预算，不用其`Usage.attempts()==0`约束日常次数。原UTC日及所有旧预留保留；grant累计次数/金额跨日不重置。
- 固定锁序settings→grant usage→部署日金额→Project日金额→requests。创建不锁金额；结果只锁所需前缀后再锁requests；已领取失败收尾/独立费用遵守相同方向。
- 只追加V17/V18；V1–V16及旧函数定义保留原字节，无历史授权回填。Agent迁移失败隔离，不影响Scanner/门禁。
- 此文档切片不执行以下任何Maven/数据库/假HTTP/容器命令；命令仅供后续实现及维护窗口结束后验证。不得在别的代理进行备份/维护时启动运行栈或测试。
- 离线源码实现可继续，合并/发布顺序固定Docs main CI→Platform main CI→Web固定快照/main CI→Deploy固定候选/默认关闭离线Compose；真实使用与新的合成退出验收分别另获具体授权。#43、4H和阶段四仍开放。

## Review Focus

- 同键重放遇到文档更新、撤销或丢响应：返回已保存快照，不重新检索/调度；任务4/8覆盖。
- 不同模式、不同grant及UTC换日遇到旧UNKNOWN：不能逃掉原日金额或重置grant计数；任务5/7覆盖。
- 风险/凭据/部署绑定在锁等待或发送后变化：发送前失败关闭，已取得可信usage照常记费；任务3/6覆盖。
- 很短文档、多片段拼合、非ASCII偏移及提示注入：不能发送完整原文或扩大最小投影；任务2覆盖。
- 成功结果提交、撤销、恢复与重复调度并发：不能反向拿锁、提前释放槽或让非claim胜者终结请求；任务6/7覆盖。

---

## 文件与交付边界

下文路径均相对本Platform仓库。为避免重复长路径，`A = src/main/java/io/github/aiarchguard/platform/agent`，`T = src/test/java/io/github/aiarchguard/platform`；`A/internal/X.java`、`T/X.java`分别是该前缀下的精确文件路径。`Create`文件目前均不存在；`Modify`文件已在基线核对。不得顺带重构其他模块。

- 公共边界：`A/DailyAgentOperations.java`包含精确日常输入/视图nested records与接口；`A/web/DailyAgentController.java`、`DailyAgentExceptionHandler.java`、`DailyAgentNoStoreFilter.java`只处理日常HTTP。
- 控制与请求：`A/internal/DailyAgentApplicationService.java`是唯一接口实现，委托`DailyAgentControlService.java`及`DailyAgentRequestService.java`；内部记录/持久化端口放`DailyAgentStore.java`。
- 快照：`A/internal/AuthorizedAgentSnapshotBuilder.java`提取最小授权/投影，原`AgentApplicationService.java`保留旧创建、失败、审计及调度规则；日常profile额外拒绝全篇投影。
- 费用与执行：新增`DailyAccountingService.java`、`LiveMonetaryBudgetStore.java`、`LiveExecutionLease.java`、`LiveRequestBinding.java`、`LiveRequestAssociation.java`及synthetic/daily两种小适配；复用现有执行核心和结果事务。
- 数据：`A/persistence/JdbcDailyAgentStore.java`、`JdbcLiveMonetaryBudgetStore.java`及两份新Agent迁移；日常不访问其他模块内部表。
- 运行与恢复：`A/internal/PersonalRuntimeSafety.java`、`DailyRuntimePolicy.java`、`PersonalLiveRuntimeResources.java`、`PersonalDailyRuntimeConfiguration.java`、`DailyAgentRecovery.java`；现有runtime/worker/recovery仅作必要模式分流。
- 所有合成fixtures放`src/test/resources/agent-daily/`；新测试以下列任务文件为准。实现者可把辅助方法留在相应测试，不引入通用workflow框架。

### Task 1: 冻结独立机器契约与Java边界

**Files:** Create `openapi/agent-daily-v1.json`；`contracts/agent-personal-daily-risk-0.1.0.schema.json`、`agent-personal-daily-grant-0.1.0.schema.json`、`agent-personal-daily-request-0.1.0.schema.json`；`A/DailyAgentOperations.java`；`T/AgentDailyContractTest.java`；`src/test/resources/agent-daily/{risk,grant,request}-valid.json`。不在本任务注册controller或运行bean。

**Interfaces:** DTO字段、枚举、null、边界和错误精确照设计§3–4；OpenAPI 3.1.0、info.version 0.1.0。公共接口为：

```java
void authorizeSelectionWrite(UUID project, String origin, boolean secure);
void authorizeControlWrite(UUID project, String origin, boolean secure);
DailyContext context(UUID project);
DailySettings settings(UUID project);
DailySettings updateSettings(UUID project, long revision, DailySettingsInput input);
Creation<DailyRiskView> acknowledge(UUID project, String key, DailyRiskInput input);
DailyRiskView getRisk(UUID project, UUID risk);
DailyRiskView revokeRisk(UUID project, UUID risk);
Creation<DailyGrantView> approveGrant(UUID project, String key, DailyGrantInput input);
DailyGrantView getGrant(UUID project, UUID grant);
DailyGrantView revokeGrant(UUID project, UUID grant);
Creation<DailyRequestView> create(UUID project, String key, DailyRequestInput input);
DailyRequestView get(UUID project, UUID request);
DailyRequestView lookup(UUID project, String key);
DailyRequestPage list(UUID project, int limit, String cursor);
record Creation<T>(T value, boolean created) {} // 只供controller选择201/200，不是wire wrapper
```

- [x] **RED:** `schemasRejectCoercionUnknownFieldsAndMissingConsent`逐个变异有效fixture：未知源码/Prompt/URL字段、缺字段、false确认、null/重复数组、无效UUID/hash、空purpose、过大数值均失败；接受schemaVersion 0.1.0。测试fixture只用合成UUID/文本，不放Key形状。
- [x] **RED:** `apiReferencesAndOldContractBytesStayStable`检查新API全部本地`$ref`可解析、response严格字段、金额nullable及原错误结构；比较基线旧API/Schema的SHA-256，不允许重生成旧文件。同键重放响应为200且不发新Location；首次201带Location。
- [x] 运行 `./mvnw -Dtest=AgentDailyContractTest test`；须因新契约/类型缺失而失败，保留RED证据。
- [x] 实现三份严格Schema、新OpenAPI及nested records。JSON Schema只验证结构；`maxCost<=maxAttempts*350000`、授权/期限/所有者等跨字段业务约束由服务/数据库双重验证，不能伪称Schema已经证明。
- [ ] 重跑本任务及`AgentContractTest,AgentEnablementContractTest,AgentLiveContractTest,AgentBatchApiContractTest`；全部通过后记录实际命令/数量，再以本任务文件做独立提交。

### Task 2: 提取快照并锁定日常最小投影

**Files:** Create `A/internal/AuthorizedAgentSnapshotBuilder.java`、`T/agent/internal/AuthorizedAgentSnapshotBuilderTest.java`；Modify `A/internal/AgentApplicationService.java`、`LiveResourceAuthorizer.java`；Extend `T/agent/internal/LiveResourceAuthorizerTest.java`及`T/AgentExplanationIntegrationTest.java`、`AgentPrSummaryIntegrationTest.java`。

**Interfaces:** 按设计§5提供`authorize(project,CreateAgentRequest):AuthorizedSelection`、`build(AuthorizedSelection,SnapshotIdentity,SnapshotProfile,String):AgentSnapshot`及两个精确record。`AuthorizedSelection`内部保存原purpose/PR、scan、按UUID排序的Finding/DocumentVersion和已验证的有界片段；不跨模块暴露。`validateDaily(AgentSnapshot):void`先执行原`validate`，再检查非全篇区间并集。

- [ ] **RED:** `legacyProjectionAndDigestRemainCompatible`固定旧synthetic模型/Prompt/Schema/价格、原digest算法、最多20 Evidence/10片段、kind代summary及alias不可见内部ID；保持旧创建failure优先级和afterCommit行为。新builder不能替旧服务决定启用、失败或调度。
- [ ] **RED:** `dailyRejectsWholeShortDocumentAndUnionCoverage`验证短文单片、两片相邻覆盖全文、重叠区间覆盖、非ASCII/代理对使用Java UTF-16偏移都400/零模型准备或发送；真正部分片段可用，不无声删片段。按公开startOffset验证边界及hash，不引入新文档接口。
- [ ] **RED:** `dailySelectionRejectsCrossProjectPrAndInjectedFields`覆盖跨Project scan/Finding/文档、错误reportSha、未完成报告、混PR/修订、别的Finding的Evidence，以及文档指令/角色声明无法增加模型字段或工具。文档有注入文本本身不视为执行许可，也不宣称所有敏感内容已被过滤。
- [ ] 先运行上述新单元测试确认RED，再抽取原授权/投影；旧profile传false并保留原算法，日常profile传true并固定既有DeepSeek常量。采用设计规定的日常固定顺序JSON digest，随机alias/requestId不入业务摘要。
- [ ] 重跑新测试及旧解释/摘要、`LiveResourceAuthorizerTest`、`AgentWorkerIsolationTest`。失败不能靠放宽旧断言修复；通过后仅提交快照相关文件。

### Task 3: V17日常风险、grant、设置与运行绑定

**Files:** Create `src/main/resources/db/agent-migration/V17__personal_daily_authorizations.sql`；`A/internal/{DailyAgentStore,DailyAgentControlService,DailyRuntimePolicy,PersonalRuntimeSafety}.java`；`A/persistence/JdbcDailyAgentStore.java`；`T/PersonalDailyAuthorizationMigrationIntegrationTest.java`、`AgentDailyControlIntegrationTest.java`、`agent/internal/DailyRuntimePolicyTest.java`。Modify `A/internal/PersonalLivePolicy.java`、`T/agent/internal/PersonalLivePolicyTest.java`。

**Interfaces:** `DailyAgentControlService`提供任务1中context/settings及风险/grant方法；`requireCurrent(UUID project,UUID grant,String purpose,LongSupplier remaining):DailyAgentStore.Grant`供请求/执行核验；`DailyRuntimePolicy.binding():DailyBindingSnapshot`、`requireConfigured():void`读取非敏感配置/凭据元数据，不消费Key。`DailyAgentStore`内部records对应设计§7的V17列；JDBC只实现这些表的精确查询、加锁、插入、撤销和usage更新。

- [ ] **RED:** `riskGrantAndSettingsAreIndependentAndDefaultOff`：初始disabled/revision0/grant=null、缺任何显式批准不启用，risk/grant写入不调用模型，旧synthetic确认永不授权daily。所有测试approvedAt/expiresAt及预算都来自显式合成fixture，不建生产默认值。
- [ ] **RED:** `bindingsAndRevocationsInvalidateNewSendsButKeepHistory`：逐字段改变owner/deployment/credential/runtimeRevision/模型映射/Prompt/Output/价格/证据摘要；未来或超过7天的checkedAt、价格过期、ack/grant越界到期均拒绝。撤销只追加，首次关闭关联设置并revision+1，重复撤销不再递增；同键同输入历史200、不延长期限，同键变更409。
- [ ] **RED:** 迁移测试在空库和只到V16的库执行V17；直接SQL尝试跨Project risk/grant、错owner/deployment/digest/credential、重复purpose、金额溢出或超上限、历史UPDATE/DELETE，均失败。锁行并发更新设置只允许匹配If-Match者；惰性settings创建不留无锁窗口。
- [ ] 分别运行 `./mvnw -Dtest=DailyRuntimePolicyTest,AgentDailyControlIntegrationTest,PersonalDailyAuthorizationMigrationIntegrationTest test`确认RED，再实现设计§3/4.1–4.3/7的限制、优先级及V17约束。sources只接受原五项固定URL；digest排序固定，保存完整binding及独立acknowledgedAt，不能用now伪造checkedAt。
- [ ] 抽出技术防护至`PersonalRuntimeSafety`，旧`PersonalLivePolicy.requireBatch`继续要求原合成Project/batch/template；不让daily开关开启synthetic runtime。重跑本任务及`PersonalLivePolicyTest,AgentPersonalEnablementIntegrationTest,AgentMigrationIsolationIntegrationTest`，通过后提交。

### Task 4: V18精确关联、幂等创建和活动槽

**Files:** Create `src/main/resources/db/agent-migration/V18__personal_daily_requests_and_accounting.sql`；`A/internal/DailyAgentRequestService.java`；`T/PersonalDailyRequestMigrationIntegrationTest.java`、`AgentDailyRequestCreationIntegrationTest.java`。Extend `DailyAgentStore.java`、`JdbcDailyAgentStore.java`；Modify `A/internal/AgentRequestRouting.java`及`T/agent/internal/AgentWorkerIsolationTest.java`。

**Interfaces:** 请求服务提供任务1的`create`；store新增`RequestLink`（设计§7精确列）、按project/request及project/owner/key查询、daily link插入与活动槽操作。创建只调用尚未装配的内部`Consumer<RequestLink>`调度依赖，不能回退`AgentWorker`。任务7负责把该依赖接入受控dispatcher；在此之前生产bean不暴露HTTP。

- [ ] **RED:** `sameKeyReplaysStoredSnapshotWithoutSearchOrSchedule`：首次requests+link+active槽+审计同事务；随后发布新文档版本、使旧grant过期/撤销、模拟响应丢失，当前有读取权限时同键返回同requestId、model_input/candidates/hash且检索/调度次数不增加。规范化集合顺序不冲突，选择/grant/内容版本不同409；先查原记录再争槽。
- [ ] **RED:** `concurrentCreateOwnsOneSlotAndOneRequest`：用闩锁强制两个同project/owner/key请求的首次历史查询都未命中，再让胜者取得settings→所选grant usage锁并提交；等待者锁后复查必须得到200，胜者201，两者requestId相同且总afterCommit调度恰好一次，不报DAILY_ACTION_IN_PROGRESS。同样的并发窗口中，同键不同选择/grant/原绑定输入返回409 IDEMPOTENCY_CONFLICT；等待期间grant撤销/过期不能遮蔽已存在的同键重放。同grant真正不同键第二个409 DAILY_ACTION_IN_PROGRESS，不创建请求或停止grant。跨模式同键409，回滚创建无调度；创建时超过6,000完整编码字节/非法投影为零请求/零attempt。
- [ ] **RED:** 直接SQL测试V18精确FK、link/attempt绑定、outcome上限、active指向其他grant拒绝；对同一requests行同时插入daily及live link，只一侧成功。两侧排他trigger先锁requests再查另一侧，旧函数原字节不动。
- [ ] 运行新测试确认RED，然后实现V18、JDBC及创建事务，严格遵循设计§4.4/7/8：首次查历史→锁settings/所选grant usage→再次按project/owner/key查历史→仅无历史时检查当前grant可发送性及active槽。锁后重放/冲突只比较已保存的grant/选择/版本摘要，不检索/调度；插入唯一键冲突仍读取胜者按同规则处理，覆盖不同grant锁域或模式的竞争。payload/candidates hash使用已持久化规范字节；业务digest使用已存文档绑定比较，不重检索。不存在关联或双关联的DeepSeek请求拒绝fake路由。
- [ ] 重跑 `AgentDailyRequestCreationIntegrationTest,PersonalDailyRequestMigrationIntegrationTest,AgentWorkerIsolationTest,AgentLiveRequestCreationIntegrationTest,LiveRequestLinkMigrationIntegrationTest,LiveTemplateSlotMigrationIntegrationTest`；证明旧模板唯一槽和旧普通API语义保持，再提交。

### Task 5: 共享纯金额日账本与日常次数/费用

**Files:** Create `A/internal/{LiveMonetaryBudgetStore,DailyAccountingService}.java`、`A/persistence/JdbcLiveMonetaryBudgetStore.java`、`T/AgentDailyAccountingIntegrationTest.java`、`AgentDailyCrossModeBudgetIntegrationTest.java`；Modify `LiveAccountingStore.java`、`A/persistence/JdbcLiveAccountingStore.java`；Extend日常store/JDBC。

**Interfaces:** `LiveMonetaryBudgetStore`是设计§7的`MoneyUsage(long reserved,long spent)`及`lock/reserve/settle`精确签名，无attempt字段。`DailyAccountingService.reserve(project,request,remaining):Admission`、`checkBeforeSend(project,request,remaining):SendApproval`、`settle(project,request,price,usage):Outcome`、`unknown(project,request):Outcome`、`stop(project,request,code):void`返回原`LiveAccountingOperations`records。`completeSuccess(project,request):void`仅加入既有结果事务，不另开事务，未SETTLED或未占槽则拒绝。

- [ ] **RED:** `reserveIsAtomicBoundedAndCountedOnce`并发同请求预留返回一个newAttempt=true；attempt、grant attempts+1、grant及两级reserved+316373、审计一起提交/回滚。次数用尽、少1 microUSD、日额不足、撤销/凭据改变/锁后到期都零新attempt。使用减法核对余额避免加法溢出。
- [ ] **RED:** `crossModeAndNewGrantSeeOldReservations`：同Project synthetic/daily共用Project和部署日行，不同Project仍共用部署行；原live UNKNOWN和daily UNKNOWN各挡住可用金额。换grant、模式或同一天重启不能绕过；synthetic只保留自己的批次次数，不能拿日行attempts=0放行。
- [ ] **RED:** `settlementUsesOriginalUtcDayAndNeverRestoresAttempts`：23:59:59预留，午夜后checkBeforeSend拒绝；可信usage次日结算仅更新原day。SETTLED实际0才显示0；UNKNOWN保留316373且actual=null、停止grant、迟到settle仍UNKNOWN。重复settle不重复扣减；grant次数跨日不减，金额累计不清零。
- [ ] **RED:** `trustedUsageSurvivesOutputFailureRevocationAndCleanupFailure`：分别注入输出/引用无效、8001输入、收到回复后撤权/关设置/轮换及结果提交失败，保留合法费用且停止grant；结算不得依赖失效授权拒记。没有可信usage或非法cost保守UNKNOWN；不能误触fake预算。
- [ ] 先运行两组新集成测试确认RED，再提取原金额SQL，原JdbcLiveAccountingStore构造与外观保留，原`finish`通过同一端口更新两日行。Daily reserve/send在原有界事务，settle/unknown/stop用独立有限cleanup；不建立HTTP记费/退款入口。
- [ ] 重跑新测试及 `AgentLiveAccountingIntegrationTest,AgentContextAccountingIntegrationTest,AgentBudgetIntegrationTest,AgentLiveDatabaseAccountingIntegrationTest,LiveCostAndClosedInventoryTest`；原UTC日金额和所有旧批次结果不变后提交。

### Task 6: 窄执行lease/association复用与成功事务

**Files:** Create `A/internal/{LiveExecutionLease,LiveRequestBinding,LiveRequestAssociation,SyntheticLiveRequestAssociation,DailyLiveRequestAssociation}.java`、`T/agent/internal/DailyLiveExecutionTest.java`、`T/AgentDailyDispatchIntegrationTest.java`、`AgentDailyResultIntegrationTest.java`；Modify `LiveExecutionCoordinator.java`、`LiveRequestDispatcher.java`、`LiveDispatchTransitions.java`、`LiveResultTransitions.java`及原对应测试。

**Interfaces:** 采用设计§6完整签名。`SyntheticLiveBinding`包装旧`LiveRequestStore.Link`，`DailyLiveBinding`包装`DailyAgentStore.RequestLink`，均实现`projectId/requestId/createdAt`；不使用占位batch/template。旧公开构造及execute(project,batch,template,request[,remaining])保持原失败关闭和委托。新增包内`execute(LiveExecutionLease,LongSupplier)`和结果`finishBounded(snapshot,result,usage,remaining,lease)`。

- [ ] **RED:** `dailyAndSyntheticShareOneSendAlgorithm`用假responses/credential source分别验证同一次prepare、唯一attempt、发送前两次复核、30秒原期限、收到回复先独立settle再Output/Citation、失败stop/unknown。所有旧响应拒绝、记录隐私及重入/迟到credential callback断言保留；不可复制coordinator实现。
- [ ] **RED:** `claimLoserCannotSendOrFailWinner`覆盖重复dispatch、CAS丢失、排队拒绝、afterCommit回滚、scope撤销、队列超过60秒、领取锁等待耗尽。无claim或newAttempt=false者不能停grant、写UNKNOWN或终结别人正在运行的请求。
- [ ] **RED:** `dailySuccessAndSlotReleaseCommitTogether`在结果事务中持settings→grant锁，再验证role/resources/current binding/SETTLED；结果CAS成功后清本请求active。结果CAS丢失、锁后deadline耗尽、审计或提交失败，SUCCEEDED和active释放一起回滚，独立SETTLED保留。与撤销/新创建并发时无反向锁，无新调用趁settle先完成而进入。
- [ ] 运行新测试确认RED，再把核心accounting参数替换为lease；synthetic适配原有语义，日常适配任务3–5。result overload的`lockAndValidateSuccess`先于请求CAS，`completeSuccess`只在CAS成功后且同事务调用；原公开result入口仍完全兼容。
- [ ] 重跑 `DailyLiveExecutionTest,AgentDailyDispatchIntegrationTest,AgentDailyResultIntegrationTest,LiveExecutionCoordinatorTest,LiveRequestDispatcherTest,LiveResultTransitionsTest,AgentLiveDispatchIntegrationTest,AgentLiveResultIntegrationTest,AgentLiveAdmissionWaitIntegrationTest`，并跑既有bounded credential/database工作测试；未扩大allowlist、未延长期限后提交。

### Task 7: 默认关闭运行装配及有界恢复

**Files:** Create `A/internal/{PersonalLiveRuntimeResources,PersonalDailyRuntimeConfiguration,DailyAgentRecovery}.java`、`T/agent/internal/PersonalDailyRuntimeTest.java`、`T/AgentDailyRecoveryIntegrationTest.java`；Modify `PersonalLiveRuntimeConfiguration.java`、`PersonalLiveRuntime.java`、`AgentRecovery.java`、`AgentTransitions.java`；Extend原runtime/executor/recovery测试与`src/main/resources/application.yml`的daily默认关闭配置。

**Interfaces:** 单一`PersonalLiveRuntimeResources`拥有现有`PersonalLiveExecutor`及其bundle关闭责任，提供受控executor/adapters访问及`requireOpen()`；两个runtime门面只借用、不重复关闭共享资源。`DailyAgentRecovery.recoverExpired(AgentSnapshot snapshot,Instant cutoff):void`只有限cleanup，不调度。`AgentRecovery`仍单轮查询≤100个过期请求，先按精确关联分流，再执行各模式原恢复。

- [ ] **RED:** `dailyRuntimeDefaultsToZeroKeyAndZeroHttp`：构造、context/设置/历史读取、保存Key、启停/撤销均不consumeKey、不初始化网络或调用模型。单独开总开关、旧live开关、daily开关、单个grant中任何一项都不足；完整合成测试配置仅能调用假HTTP，不能解析/访问官方目标。
- [ ] **RED:** `sharedResourcesCloseOnceAndPreserveLegacyBatchPolicy`：两模式一个worker/有界队列；关闭中断在途、丢弃队列、实际await终止且bundle关闭一次；关闭后零新发送，原`requireBatch`未因daily而放宽，缺source/check/安全属性不能借模式回退。旧独立构造测试保留自己的资源所有权。
- [ ] **RED:** `recoveryNeverResendsAndKeepsSettlement`分四类：QUEUED/RUNNING无attempt→未发失败+停grant；有attempt无outcome→UNKNOWN+原day reserved；已有SETTLED→保留费用、失败结果+停grant；终态不覆盖。模拟进程中断/清理失败和迟到回复，次数/active槽不被重置，恢复不读Key、不退款、不重发。
- [ ] **RED:** `recoveryAndResultUseSameLockDirection`用闩锁并发恢复、成功提交及撤销；每请求只执行一条恢复路径，读取/claim按原始期限，结果事务与恢复不互相反向锁。双关联损坏失败关闭，不选任意模式。对旧synthetic/live各验证既有恢复终态与审计不变。
- [ ] 先运行新测试确认RED，接入同一固定adapter并将任务4 afterCommit连接至任务6dispatcher；只把daily新配置设为false/空，不填写真实runtimeRevision/check/evidence或预算。不得修改传输协议、JVM重试限制或测试依赖。
- [ ] 重跑 `PersonalDailyRuntimeTest,AgentDailyRecoveryIntegrationTest,PersonalLiveRuntimeTest,PersonalLiveExecutorTest,AgentRecoveryIntegrationTest,AgentRecoveryTest,BoundedCredentialRuntimeBoundaryTest,AgentMigrationIsolationIntegrationTest`；结束后提交，仍不打开HTTP路由或实际出口。

### Task 8: HTTP操作、只读恢复和旧入口防旁路

**Files:** Create `A/internal/DailyAgentApplicationService.java`；`A/web/{DailyAgentController,DailyAgentExceptionHandler,DailyAgentNoStoreFilter}.java`；`T/AgentDailyApiIntegrationTest.java`、`AgentDailyReadIntegrationTest.java`、`agent/web/DailyAgentControllerTest.java`。Extend日常request service/store/JDBC；Modify `A/internal/AgentApplicationService.java`的get防旁路，按需在现有引用读取路径增加相同daily guard。

**Interfaces:** 唯一公共实现委托任务3/4服务。request service提供任务1的get/lookup/list，返回既有AgentRequestView的严格wrapper，不污染旧API。列表cursor为最多512个base64url字符的(createdAt,id)，limit 1..50，默认20；SQL永远限定owner/project/daily关联、降序键集，不把cursor当授权。没有记录404，不创建/调度。

- [ ] **RED:** `controllerAuthorizesBeforeReadingStrictBody`用无法读取的body验证先owner/角色/origin/secure授权；解释Viewer可提交、摘要再需Maintainer、控制写需Maintainer。覆盖8192/8193字节、重复key/尾随/scalar coercion、空revoke body、If-Match缺失428/过期412、静态lookup路由优先、所有成功/错误no-store及ETag。
- [ ] **RED:** `historyRestoresSameRequestAcrossRefreshAndRelogin`按lookup→GET→列表模拟丢响应/刷新/重新登录、同createdAt分页、删除成员权限及文档更新；不重复/漏页、不调度、不再搜索新版本。过期/撤销grant历史在当前角色允许时可读；费用PENDING/UNKNOWN保持null，只有可信SETTLED 0显示0。
- [ ] **RED:** `allReadPathsReauthorizeOwnerAndCitations`：另owner、跨Project或已失权者对daily GET/list/lookup、旧`/agent/requests/{id}`及引用解析均不能读；摘要Maintainer被降为Viewer时资源核验拒绝。原synthetic/live当前授权读取仍可用，无daily状态提前泄露资源。
- [ ] 运行上述测试确认RED，按设计§4实现固定错误/优先级、201首次/200重放、严格读取及只读查询。读取只用持久化snapshot和当前公开资源校验，不显示未经验证raw输出，不在浏览器持久化结果/Token/授权。
- [ ] 重跑 `DailyAgentControllerTest,AgentDailyApiIntegrationTest,AgentDailyReadIntegrationTest,AgentExplanationIntegrationTest,AgentPrSummaryIntegrationTest,AgentDisabledIntegrationTest,AgentUnapprovedAdapterIntegrationTest,AgentBatchExecutionApiIntegrationTest`及任务1契约测试；全部通过后提交。

### Task 9: 完整兼容证据与消费者交接

**Files:** Update本文、生产者技术设计、`README.md`的真实当前状态；新增结果记录`docs/technical-design/v0.5-agent-personal-project-use-validation.md`。按实际修复更新相应测试，不改旧契约/迁移/断言来降低门槛。不修改Web/Deploy仓库。

**Interfaces:** 交接版本为Daily API 0.1.0+三份Schema 0.1.0+追加V17/V18；仍支持旧普通/enablement 0.4.0/live 0.1.0/batch 0.3.0。只提供假HTTP离线能力证据；实际Key、授权额度及部署组合不由测试报告生成。

- [ ] 运行全部新增及上列最小测试后，运行 `./mvnw --batch-mode --no-transfer-progress verify`（Windows用`.\mvnw.cmd`）；确认JUnit报告零失败、架构/Modulith规则通过。涉及迁移必须同时证明空库与V16升级；无法运行的项目逐一标未执行，不等同通过。
- [ ] 按现有`.github/workflows/baseline.yml`运行治理所需检查与`git diff --check`；待维护结束且具备运行条件时再构建既有Dockerfile，不能在维护期间运行。原依赖收敛、LICENSE、固定版本、secret/log隐私和默认关闭检查保持，不新增依赖。
- [ ] 验证禁用daily时主Scanner报告、Finding、baseline、exception、gate PASS/FAIL和CI语义不变；模拟Agent迁移失败仍主功能可用。以当前测试证明，不引用旧报告替代。
- [ ] 在假HTTP与合成数据库中演练：关Web新入口/Project/daily及总出口→零新attempt→由新版本完成所有在途/UNKNOWN收尾→证明无未终结daily请求→回退兼容应用。迁移、grant、密文、快照、费用/UNKNOWN、审计保留，不down migration、不修checksum；旧应用不承担新会计恢复。
- [ ] 独立技术评审检查全部任务接口、旧回归和恢复竞态；清楚记录实际运行命令、结果、未跑项及剩余外部关卡。真实提供方兼容仍需自身当前证据，官方文档/假HTTP不能证明历史失败已修复。
- [ ] 本地可保存完成的实现提交；跨仓库合入先核实Docs规范main CI，再Platform PR/main CI。向Web交付固定OpenAPI/Schema摘要、上下文与无默认预算设置流程、所选资源动作及lookup/list恢复，再由Web main CI后交Deploy默认关闭候选。新的合成验收和日常金额/次数/有效期/材料与具体运行绑定均待独立明确授权，不因实现完毕自动启用或关闭阶段。

## 本计划自检与交接状态

设计§1–4对应任务1/3/8；§5对应任务2/4；§6对应任务6/7；§7对应任务3–5；§8对应任务4–7；§9对应任务9。五项Review Focus均有具体测试归属，公共DTO和新内部接口遵循同一生产者设计。日常全篇区间使用公开UTF-16偏移，纯金额端口无次数，成功释放槽在原结果事务内且锁序明确。内部小适配不引入新服务、通用工作流或所有者重复审批流程。

2026-10-30已完成生产者设计评审及任务1的独立机器契约、DTO、合成 fixtures 和结构测试。LF/CRLF 校验基线缺陷已有失败回归、修复及独立复审；最终15项所选离线测试通过。完整 enablement 测试中的一项会静态启动数据库，维护期间未在本机运行，任务1最后一步及其余任务仍未勾选，等待托管完整 CI。不把结构测试记为迁移、运行时、真实 HTTP 或部署成功；维护和真实外发授权限制继续有效。
