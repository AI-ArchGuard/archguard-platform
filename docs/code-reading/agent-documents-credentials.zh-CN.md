# Agent、文档与凭据中文阅读指南

建议先读 [ScanJob/Finding](scanjob-finding.zh-CN.md) 和 [Governance](governance.zh-CN.md)，理解确定性结果如何产生，再阅读建议层。这里描述当前实现，不能代替真实模型验收或阶段退出证明。

## 先分清三条链

- 文档：Maintainer 显式上传 → 不可变版本与摘要 → 明确选定版本 → PostgreSQL 有界检索。没有克隆 Git 或向量数据库。
- 原合成 Agent：用户选 Finding/文档版本 → 保存输入快照及幂等记录 → 提交后调度 → 假模型 → Schema/引用/追溯校验 → 保存独立解释。不能通过原入口悄悄启用真实提供方。
- 个人 live：专用 owner、Project 设置、风险确认、当前凭据版本、合成批次、来源证明、运行绑定及多个开关共同约束独立入口。能保存 Key、能读状态、已创建批次都不等于可以发送。

## 从公开边界进入

1. [AgentOperations](../../src/main/java/io/github/aiarchguard/platform/agent/AgentOperations.java) 与 [AgentRequestView](../../src/main/java/io/github/aiarchguard/platform/agent/AgentRequestView.java)：先认识创建、读取、状态与版本绑定。
2. [AgentApplicationService](../../src/main/java/io/github/aiarchguard/platform/agent/internal/AgentApplicationService.java)：阅读 Project Viewer 校验、扫描摘要/PR 修订绑定、Finding 与文档候选引用、输入摘要及幂等冲突。它调用其他模块公开接口，不把模型输出写回 Finding 或门禁。
3. [AgentWorker](../../src/main/java/io/github/aiarchguard/platform/agent/internal/AgentWorker.java)：理解提交后的后台执行、失败分类与结果接受；请求排队成功不表示解释成功。
4. [AgentModelPort](../../src/main/java/io/github/aiarchguard/platform/agent/AgentModelPort.java)：查看发给模型的投影和模型返回值，区分可信输入绑定与不可信输出。

阅读复杂实现时，按“创建记录 → 调度 → 发送前再次校验 → 响应校验 → 结算/终态”的顺序跟踪。不要把不同步骤的事务误读为覆盖网络调用的单个事务。

## 个人真实调用

从 [接线设计](../technical-design/v0.5-agent-personal-live-runtime.md) 进入，再看 [PersonalLiveRuntime](../../src/main/java/io/github/aiarchguard/platform/agent/internal/PersonalLiveRuntime.java)、[PersonalLiveExecutor](../../src/main/java/io/github/aiarchguard/platform/agent/internal/PersonalLiveExecutor.java)、[LiveExecutionCoordinator](../../src/main/java/io/github/aiarchguard/platform/agent/internal/LiveExecutionCoordinator.java) 和 [LiveRequestDispatcher](../../src/main/java/io/github/aiarchguard/platform/agent/internal/LiveRequestDispatcher.java)。

关注以下问题：身份是否仍有效，Project/资源授权是否仍成立，当前凭据是否与确认绑定一致，发送槽位能否重复消费，预算是否只能缩短，以及失败之后是否停止后续模板。输入 Token 阈值、费用预留、实际用量、UNKNOWN 和提供方账单是不同概念；本地上限不能解释成提供方硬限费。

[DeepSeekResponsesCodec](../../src/main/java/io/github/aiarchguard/platform/agent/infrastructure/DeepSeekResponsesCodec.java) 及其相关客户端处理官方协议封装；模型“返回了 JSON”之后仍需经过业务 Schema、模型/版本、引用、用量和费用检查。脱敏诊断只保存固定类别或有界计数，不保存原始响应，不能据此还原历史失败正文。

## 文档上传与检索

- [DocumentOperations](../../src/main/java/io/github/aiarchguard/platform/agentdocument/DocumentOperations.java) 是跨模块公开边界。
- [DocumentApplicationService](../../src/main/java/io/github/aiarchguard/platform/agentdocument/internal/DocumentApplicationService.java) 组织 Maintainer 上传、Viewer 读取、不可变版本及明确版本集合的检索；任一版本不可见时整体拒绝。
- 文档内容是数据，不能给模型新增权限。引用绑定版本及摘要，不能在文档更新后静默指向最新版本。

## 凭据入口与消费

先读 [只写凭据设计](../technical-design/v0.5-personal-credential-management.md)，再看 [CredentialApplicationService](../../src/main/java/io/github/aiarchguard/platform/agentcredential/internal/CredentialApplicationService.java) 与 [EncryptedCredentialStore](../../src/main/java/io/github/aiarchguard/platform/agentcredential/internal/EncryptedCredentialStore.java)。

浏览器提交 Key 后只得到版本等元数据，不能通过读取 API 取回明文。运行时主密钥与密文分离，受控回调只消费期望版本。凭据锁、owner 校验、有界审计和剩余预算共同限制消费；存储成功不验证 Key 有效性，也不查询余额或开启 Agent。解释这些代码不需要读取本机真实主密钥、密文或 Key。

## 测试怎么读

从 [AgentExplanationIntegrationTest](../../src/test/java/io/github/aiarchguard/platform/AgentExplanationIntegrationTest.java)、[AgentPrSummaryIntegrationTest](../../src/test/java/io/github/aiarchguard/platform/AgentPrSummaryIntegrationTest.java)、[PersonalLiveRuntimeIntegrationTest](../../src/test/java/io/github/aiarchguard/platform/PersonalLiveRuntimeIntegrationTest.java) 进入，再按目录读 codec、预算、授权与凭据测试。

假 HTTP、临时数据库和固定合成数据用于检查代码边界，不是收费真实调用、生产身份服务、提供方地区/保留策略或阶段发布的证明。原迁移、JSON Schema、契约和 fixture 保持字节不变，通过代码注释与本指南理解。
