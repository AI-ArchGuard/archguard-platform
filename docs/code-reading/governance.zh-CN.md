# Governance 中文阅读指南

先读 [ScanJob／Finding 指南](scanjob-finding.zh-CN.md)，再看本篇。Governance 使用完整的确定性扫描报告，把“这次扫描发现了什么”连接到“相对基线新增了什么、是否应阻断 CI、PR 内发生了什么变化”。它在 Platform 模块化单体内编排，不下载源码或执行 Git clone。

本批覆盖82个生产文件和6个核心测试，累计204个 Java 文件。本指南只说明当前实现；实际验证和剩余范围见[分批计划](../superpowers/plans/2026-10-24-project-chinese-comments.md)。

## 先辨认三种结论

- ScanJob 的 `SUCCEEDED` 表示扫描执行成功，不表示没有违规。
- [ComparisonView](../../src/main/java/io/github/aiarchguard/platform/governance/ComparisonView.java) 描述候选相对某个基线版本的 `NEW`、`EXISTING`、`RESOLVED` 集合。
- [GateEvaluationView](../../src/main/java/io/github/aiarchguard/platform/governance/GateEvaluationView.java) 记录策略求值的 `PASS`、`FAIL` 或 `ERROR`、CI 退出码、基线/比较/策略版本和命中的例外。

“相对基线已解决”与“相对 PR 前一修订已解决”还使用不同参考点，后文会分别说明。

## 1. 找到比较空间与稳定身份

从 [GovernanceScopeInput](../../src/main/java/io/github/aiarchguard/platform/governance/GovernanceScopeInput.java)、[GitRevision](../../src/main/java/io/github/aiarchguard/platform/governance/GitRevision.java) 和 [BaselineOperations](../../src/main/java/io/github/aiarchguard/platform/governance/BaselineOperations.java) 开始。比较空间固定为 `Project + Repository + targetBranch + RuleSetVersion`；不同规则版本没有隐式共用基线的能力。Git commit 与分支元数据首先是经过认证的提交者提供的断言，不能由此证明 Git 提交的祖先关系。

[ReportFingerprintComputer](../../src/main/java/io/github/aiarchguard/platform/governance/internal/ReportFingerprintComputer.java) 从报告中提取逻辑实体、规则与违规区分字段，构造规范载荷，再计算 `platform-finding-v1` 指纹。[FindingSnapshot](../../src/main/java/io/github/aiarchguard/platform/governance/internal/FindingSnapshot.java) 同时保存载荷摘要和当次 Scanner ID：前者用于检测身份冲突，后者用于回到原报告追溯。Scanner ID、行列号、展示消息、严重性等不被直接当成跨扫描逻辑身份；路径只有作为规范逻辑实体身份的一部分时才参与。

## 2. 从成功扫描提升不可变基线

阅读路径为 [BaselineController](../../src/main/java/io/github/aiarchguard/platform/governance/web/BaselineController.java) → [BaselineApplicationService](../../src/main/java/io/github/aiarchguard/platform/governance/internal/BaselineApplicationService.java) → [JdbcBaselineStore](../../src/main/java/io/github/aiarchguard/platform/governance/persistence/JdbcBaselineStore.java)。应用层先校验 Project 权限、仓库/规则版本与成功任务的归属，再计算指纹快照。

`promote`、`select`、`compare` 的事务位于应用服务。存储锁定比较空间，序列化该空间的版本分配和活动选择。基线版本内容与活动指针分开：重复提升同一扫描可重新选择原版本，活动选择记录与 `selectionVersion` 仍会追加/增加，不等于这个操作完全没有新写入。封存与不可变限制还依赖既有数据库触发器，应连同存储代码和原迁移阅读；本批不改迁移。

[FindingClassifier](../../src/main/java/io/github/aiarchguard/platform/governance/internal/FindingClassifier.java) 对基线集合 `B`、候选集合 `C` 做集合运算：`NEW=C−B`、`EXISTING=C∩B`、`RESOLVED=B−C`，然后固定排序。相同逻辑指纹对应不同载荷不能静默合并。比较绑定确切基线版本和候选任务，活动基线后来切换不会改写已保存的比较。

## 3. 默认门禁与有期限例外

从 [GateController](../../src/main/java/io/github/aiarchguard/platform/governance/web/GateController.java) 进入 [GateEvaluationApplicationService](../../src/main/java/io/github/aiarchguard/platform/governance/internal/GateEvaluationApplicationService.java)，再读纯策略 [GatePolicy](../../src/main/java/io/github/aiarchguard/platform/governance/internal/GatePolicy.java)。默认只让未被有效例外覆盖的 `NEW high/critical` 阻断；存量和已解决项默认不阻断。

[PolicyExceptionApplicationService](../../src/main/java/io/github/aiarchguard/platform/governance/internal/PolicyExceptionApplicationService.java) 管理独立的指纹/规则范围例外；[PolicyExceptionRecord](../../src/main/java/io/github/aiarchguard/platform/governance/internal/PolicyExceptionRecord.java) 按求值时间判断生效、到期和撤销。有效区间为 `[effectiveAt, expiresAt)`，创建限制还包括最长30天。例外及撤销记录不改写历史门禁；它与单次 Finding 的人工处置属于不同用例。

门禁结果及对应退出码为：`PASS→0`、`FAIL→2`、配置 `ERROR→64`、执行或契约 `ERROR→70`。无基线不能当作空集合而通过。服务把已明确处理的比较/契约问题转换为错误结果，没有把所有异常吞掉变成 PASS。计算和 [GateWriteService](../../src/main/java/io/github/aiarchguard/platform/governance/internal/GateWriteService.java) 的持久化事务分开，使已识别的错误也能形成记录；不要据此推导所有读取/计算步骤都处于同一个数据库快照。

门禁幂等键按 Project 隔离。同键同输入返回已保存结果，即使之后例外或活动基线改变；希望重新求值需新键。[JdbcGateEvaluationStore](../../src/main/java/io/github/aiarchguard/platform/governance/persistence/JdbcGateEvaluationStore.java) 保存并封存历史，而不是把新策略判断覆盖到旧门禁行上。

## 4. CI 报告接收如何支持中断恢复

从 [ReportSubmissionController](../../src/main/java/io/github/aiarchguard/platform/governance/web/ReportSubmissionController.java) 进入 [ReportSubmissionApplicationService](../../src/main/java/io/github/aiarchguard/platform/governance/internal/ReportSubmissionApplicationService.java)。报告以原字节和最小 Git 元数据提交；[SubmissionContract](../../src/main/java/io/github/aiarchguard/platform/governance/internal/SubmissionContract.java) 检查版本、大小、UTF-8、原字节摘要及 Git/PR 输入。完整 Result Schema 和引用验证由后续导入链复用 Finding 的接收入口完成，不能把元数据校验器本身当作完整报告消费者。

提交幂等同时使用 Project 内的键和请求摘要。请求摘要包含冻结顺序的多项元数据及原报告摘要，不等于报告 SHA-256，也不是门禁用的另一种请求摘要。重复键异义报冲突；同一请求摘要换键也可返回原提交。原字节报告不会为计算摘要而重新格式化。

首次接受时，[ReportSubmissionWriteService](../../src/main/java/io/github/aiarchguard/platform/governance/internal/ReportSubmissionWriteService.java) 在一个事务中插入 `RECEIVED`、经 ScanJob 导入报告/Finding 并记录接收审计。随后在当前调用中求门禁，再由 [ReportSubmissionFinalizeService](../../src/main/java/io/github/aiarchguard/platform/governance/internal/ReportSubmissionFinalizeService.java) 的另一事务完成提交及必要的当前 PR 关联。返回 `202` 不证明存在后台队列或异步线程。

若接收事务已提交而后续尚未完成，同键重放会继续原提交的求值/完成；这不是重跑 Scanner。只有 `COMPLETED` 且有关联门禁时才提供提交门禁视图。分段持久化与幂等约束支持已覆盖的恢复路径，不等于整个请求只有一个全局事务，也不等于所有外部副作用恰好执行一次。

## 5. Webhook 的可信边界与当前 head

[GithubGovernanceApplicationService](../../src/main/java/io/github/aiarchguard/platform/governance/internal/GithubGovernanceApplicationService.java) 管理绑定和 Webhook。Repository 的 GitHub 数字 ID 是稳定身份，`owner/name` 是展示信息。绑定需 Maintainer；Webhook 通过原始正文 HMAC 验签，再按事件/动作、仓库绑定和正文时间检查，而不是依赖某个浏览器用户自报身份。

正文上限为2 MiB；对于受支持且已绑定的 PR 事件，还检查正文 `updated_at` 相对服务器时间±10分钟的窗口。其他事件或未绑定仓库有各自的忽略处置，不能说它们也已通过 PR 时间检查。同 delivery、同正文重放返回原处置，不重新应用 head；同 delivery 不同正文冲突。实际 head 推进条件在 [JdbcGithubWebhookStore](../../src/main/java/io/github/aiarchguard/platform/governance/persistence/JdbcGithubWebhookStore.java) 中，只有应用成功的 head 才追加修订历史。

报告 commit 与当前 head 匹配时才可关联当前门禁；旧报告可以保留历史，却不能把新 head 的当前结果替换成旧结果。Webhook 还会查找先前已完成的匹配报告，处理报告先到、事件后到的情况。这些都是已验证事件与保存状态之间的关联，不证明 Git ancestry。Platform 不在这里持有 GitHub 发布令牌或发布外部 Commit Status，外部 CI 消费者的投递保证需另看其实现。

## 6. PR 修订差异为何不改变门禁

阅读 [GovernanceReadService](../../src/main/java/io/github/aiarchguard/platform/governance/internal/GovernanceReadService.java) 的 `prRevisionDelta`、[PrRevisionDeltaStore](../../src/main/java/io/github/aiarchguard/platform/governance/internal/PrRevisionDeltaStore.java) 及其 [JDBC 实现](../../src/main/java/io/github/aiarchguard/platform/governance/persistence/JdbcPrRevisionDeltaStore.java)。应用方法使用只读 `REPEATABLE_READ` 事务，结合当前 head、前一个已应用且不同的 head、对应完成报告和比较快照。

这里以 PR 前一候选集合 `P` 为参考，而门禁以基线 `B` 为参考。干净基线下，PR 首次新增问题又修复后，基线 `RESOLVED` 可以是0，PR差异 `RESOLVED` 是1，二者都符合各自定义。PR差异只读、不参与门禁和 CI 退出码。

没有前一修订、缺少当前/前一完成报告或指纹版本不兼容时返回明确不可用状态，不能把未知当作空集合或用报告到达顺序猜测前驱。代码从已有比较中排除基线 `RESOLVED` 项以还原各候选集合，再重新分类；它不是第二次 Scanner 扫描。

## 7. 读取视图、授权和失败审计

[GovernanceReadController](../../src/main/java/io/github/aiarchguard/platform/governance/web/GovernanceReadController.java) 为 Web 提供门禁历史、比较及 PR 查询。应用层先检查 Viewer 与仓库归属；分页页码从0开始、页大小1–50，读取多一条以生成 [GovernancePage](../../src/main/java/io/github/aiarchguard/platform/governance/GovernancePage.java) 的 `hasMore`，不把不同规则版本的门禁历史混为同一比较空间。

内部 Store 的 Project/Repository 条件用于限定数据范围，不替代应用层用户授权。[GovernanceWriteAuditInterceptor](../../src/main/java/io/github/aiarchguard/platform/governance/web/GovernanceWriteAuditInterceptor.java) 只为进入 MVC 的治理写入拒绝/失败追加有限审计；元数据不含报告正文、签名或凭据。身份层提前拒绝、没有进入 MVC 的请求不由这条拦截器覆盖，也不能把这项日志补充当作整个请求的全局原子审计保证。

## 测试怎么读

4个内部测试分别验证[分类](../../src/test/java/io/github/aiarchguard/platform/governance/internal/FindingClassifierTest.java)、[门禁策略](../../src/test/java/io/github/aiarchguard/platform/governance/internal/GatePolicyTest.java)、[指纹](../../src/test/java/io/github/aiarchguard/platform/governance/internal/ReportFingerprintComputerTest.java)和[提交元数据契约](../../src/test/java/io/github/aiarchguard/platform/governance/internal/SubmissionContractTest.java)，共12个场景。后两者含最小片段，不能据此宣称它们验证了所有完整 Result Schema 边界。

[GovernanceApiIntegrationTest](../../src/test/java/io/github/aiarchguard/platform/GovernanceApiIntegrationTest.java) 的4个场景以真实测试 PostgreSQL 检查基线、封存历史、例外与授权；[GovernanceCiApiIntegrationTest](../../src/test/java/io/github/aiarchguard/platform/GovernanceCiApiIntegrationTest.java) 的4个场景用合成签名和 MockMvc 连接报告提交、head、门禁及 PR差异。恢复用例直接构造数据库断点，不是真实杀死 JVM；这些测试不代表真实公网 GitHub 投递、完整生产 OIDC 或真实 CI 验收。

本批只补上述82个生产文件、6个测试及阅读说明。其余治理契约/迁移测试、Agent、文档/凭据、身份/通用/审计模块和其他仓库仍待后续批次；已发布 SQL、生成文件、机器契约和固定 fixture 保持原样。
