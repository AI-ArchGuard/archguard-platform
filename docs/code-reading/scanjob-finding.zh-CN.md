# ScanJob 与 Finding 中文阅读指南

建议先读 [Repository／RuleSet](repository-ruleset.zh-CN.md)，理解扫描目录和不可变规则版本，再从本篇跟踪“提交任务 → 接受报告 → 查看证据 → 人工处置”。本篇与第三批中文注释只解释当前实现，不改变状态机、Scanner 契约或运行环境。

## 先认识几个业务对象

- [ScanJobView](../../src/main/java/io/github/aiarchguard/platform/scanjob/ScanJobView.java) 是任务对外视图；内部 [ScanJobRecord](../../src/main/java/io/github/aiarchguard/platform/scanjob/internal/ScanJobRecord.java) 还包含幂等摘要、租约、attempt token 和报告字节。
- [ScanJobStatus](../../src/main/java/io/github/aiarchguard/platform/scanjob/ScanJobStatus.java) 表示执行进度。[ScanJobOutcome](../../src/main/java/io/github/aiarchguard/platform/scanjob/ScanJobOutcome.java) 表示成功执行后的规则结论：`SUCCEEDED` 仍可能是 `VIOLATION`，不能把执行成功等同于规则全部通过。
- [FindingView](../../src/main/java/io/github/aiarchguard/platform/finding/FindingView.java) 是可查询的问题，[EvidenceView](../../src/main/java/io/github/aiarchguard/platform/finding/EvidenceView.java) 给出支持该问题的证据和位置；两者来自 Scanner 报告，Agent 不负责产生或改写这些事实。
- [FindingDisposition](../../src/main/java/io/github/aiarchguard/platform/finding/FindingDisposition.java) 是本次扫描的人工处置状态；[DispositionView](../../src/main/java/io/github/aiarchguard/platform/finding/DispositionView.java) 记录每次处置的原因、操作者和版本。它们不自动变成跨扫描的规则例外。

## 1. 从 HTTP 提交到数据库任务

从 [ScanJobController](../../src/main/java/io/github/aiarchguard/platform/scanjob/web/ScanJobController.java) 进入 [ScanJobOperations](../../src/main/java/io/github/aiarchguard/platform/scanjob/ScanJobOperations.java)，再读 [ScanJobApplicationService](../../src/main/java/io/github/aiarchguard/platform/scanjob/internal/ScanJobApplicationService.java)。Controller 处理路径、请求和响应；应用服务检查 Project 权限、仓库归属及规则版本。

提交需 Maintainer，查询需 Viewer。幂等键的作用域是 Project；请求摘要由仓库 ID 和规则版本 ID 计算。相同键、相同摘要返回原任务，不同摘要报冲突；并发插入通过数据库唯一约束及冲突后重读处理。提交仅产生 `QUEUED`，不会在 HTTP 线程里执行 Scanner。这个服务没有包住“任务写入＋成功审计”的整体事务。

再读 [ScanJobStore](../../src/main/java/io/github/aiarchguard/platform/scanjob/internal/ScanJobStore.java) 和 [JdbcScanJobStore](../../src/main/java/io/github/aiarchguard/platform/scanjob/persistence/JdbcScanJobStore.java)。`claim` 在事务中以 `FOR UPDATE SKIP LOCKED` 选择排队任务或租约已过期的运行任务，增加 attempt 并换发 token。`complete`／`fail` 只更新 token 和状态仍匹配的行，因此旧 attempt 不能覆盖当前任务状态。方法返回的布尔值也需要与调用方如何处理它一起阅读。

## 2. 文件邮箱如何衔接独立 Runner

[MailboxScanJobCoordinator](../../src/main/java/io/github/aiarchguard/platform/scanjob/internal/MailboxScanJobCoordinator.java) 负责定时认领、写请求和收集状态；它调用公开 Repository／RuleSet／Result 接口，不把 Scanner 实现嵌入 Platform。请求携带协议版本、job/token、受控相对目录、逻辑 identity、规则内容及 Scanner 版本；实际 Scanner 进程由独立 Runner 执行。

请求先写临时文件再移动；文件系统不支持原子移动时会降级为普通替换，不能声称所有环境都有原子发布保证。状态文件名关联 job/token；未知任务、旧 token 或不适用状态的文件会走清理分支。退出码 `0`／`2` 先经 Result 接受校验，再尝试完成任务；邮箱成功路径传入的 Scanner 标签固定为 `0.2.1`。退出码 `70` 的报告在当前邮箱实现中只经过大小读取和摘要计算后作为失败附件，不调用成功报告消费器，也不生成 Finding；不要把历史设计中的“合法部分报告”理解成此分支已经做了相同的完整校验。

取消需同时阅读 [RunnerCancellationPort](../../src/main/java/io/github/aiarchguard/platform/scanjob/internal/RunnerCancellationPort.java) 和 [MailboxRunnerCancellationAdapter](../../src/main/java/io/github/aiarchguard/platform/scanjob/internal/MailboxRunnerCancellationAdapter.java)：排队任务可直接取消，运行任务先变成 `CANCEL_REQUESTED` 并尽力写信号文件；这不表示进程已经退出。现有 `claim` 不认领取消中的任务，也不负责把耗尽重试次数的运行任务终结，不能从“有租约”推导必然最终恢复。清理和隔离文件也是尽力操作，没有本类提供的后台补偿承诺。

## 3. 原始报告如何变成 Finding／Evidence

从 [ResultAcceptance](../../src/main/java/io/github/aiarchguard/platform/finding/ResultAcceptance.java) 到 [ResultReportConsumer](../../src/main/java/io/github/aiarchguard/platform/finding/internal/ResultReportConsumer.java)：先检查字节大小和固定 Schema `0.1.0`，再检查 identity、全局 ID 唯一及引用闭合。该内部入口不自行鉴别用户身份；调用方必须先建立任务、Project 及来源上下文。

规范化时，以 job ID 和 Scanner 对象 ID 生成稳定 UUID，使同一报告中的引用能转换为数据库关联；这不等于跨扫描问题 ID，跨扫描比较使用 fingerprint 等业务字段。[NormalizedFinding](../../src/main/java/io/github/aiarchguard/platform/finding/internal/NormalizedFinding.java) 和 [NormalizedEvidence](../../src/main/java/io/github/aiarchguard/platform/finding/internal/NormalizedEvidence.java) 是写入用结构。[AcceptedResult](../../src/main/java/io/github/aiarchguard/platform/finding/AcceptedResult.java) 返回原始字节 SHA-256、调用方提供的 Scanner 标签及固定 Schema 版本；这里没有另外从报告中查证 Scanner 标签。

[JdbcFindingIngestionStore](../../src/main/java/io/github/aiarchguard/platform/finding/persistence/JdbcFindingIngestionStore.java) 在事务中写入证据、问题和关联，初始处置为 `OPEN`、版本为零。它不是通用的“同一 job 重放总会成功”的幂等接口：已有 Finding 时拒绝，其余重复由实际插入和数据库约束决定。原始报告由 ScanJob 存储，不由本消费器保存或重新序列化。名称为 [ScanJobResult](../../src/main/java/io/github/aiarchguard/platform/scanjob/ScanJobResult.java) 的 `canonicalJson` 字段在此链路承载接受时的原字节，下载响应使用摘要作为 ETag。

## 4. 区分邮箱与 CI 导入的事务边界

邮箱协调器先写规范化结果，再单独调用任务完成，没有覆盖两者的整体事务；token 的条件更新只保护任务行，不能保证先前 Finding 写入随更新失败自动撤销。取消或并发竞态的所有副作用也不能仅由任务状态推断。

另一条 [ScanJobImportOperations](../../src/main/java/io/github/aiarchguard/platform/scanjob/ScanJobImportOperations.java) → [ScanJobImportService](../../src/main/java/io/github/aiarchguard/platform/scanjob/internal/ScanJobImportService.java) 路径供已完成 CI 报告导入：当前用户仍需 Maintainer，方法上的事务覆盖建任务、消费结果和标记完成，并按 `findings` 是否为空推导 outcome。它不排队执行 Runner，也不是免授权的公开上传捷径。不要把这条路径的事务注解套用到邮箱流程。

## 5. 查询与人工处置

从 [FindingController](../../src/main/java/io/github/aiarchguard/platform/finding/web/FindingController.java) 进入 [FindingOperations](../../src/main/java/io/github/aiarchguard/platform/finding/FindingOperations.java) 和 [FindingApplicationService](../../src/main/java/io/github/aiarchguard/platform/finding/internal/FindingApplicationService.java)。读取使用 Viewer 权限；查证据／问题时同时限定 Project、job 和对象 ID。列表直接查询指定范围，没有单独证明任务已成功的步骤，空列表不能说明扫描结果一定为 PASS。

处置要求 Maintainer、原因及非负期望版本；[JdbcFindingStore](../../src/main/java/io/github/aiarchguard/platform/finding/persistence/JdbcFindingStore.java) 用版本条件更新，并在同一存储事务中追加处置历史；未更新到行时返回版本冲突。应用层随后单独写审计，因此不能声称“状态、历史、审计三者”在这个服务里由同一个整体事务提交。处置不修改原始报告、规则定义或自动转化为门禁例外。

## 测试和本批覆盖边界

[ResultReportConsumerTest](../../src/test/java/io/github/aiarchguard/platform/finding/internal/ResultReportConsumerTest.java) 的三个既有场景使用固定合成报告及内存捕获器：正常规范化、身份不符拒绝和固定 Schema 拒绝。它不证明真实数据库约束、并发重放、所有引用错误或独立 Runner 的行为。[GovernanceApiIntegrationTest](../../src/test/java/io/github/aiarchguard/platform/GovernanceApiIntegrationTest.java) 的四个既有场景验证消费链中的基线、分类、门禁和跨 Project 边界，也不能替代真实邮箱端到端验收。

本批为 ScanJob 20个、Finding 21个生产文件及1个测试文件补注释，连同前两批累计116个 Java 文件。日志中的实际验证结果另记于[分批计划](../superpowers/plans/2026-10-24-project-chinese-comments.md)；未运行的验证不据此宣称通过。Governance、Agent、Identity／Audit／Common 等后续模块及其他仓库仍待分批处理；生成文件、迁移、契约和固定 fixture 保持原样。
