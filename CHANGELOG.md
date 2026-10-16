# Changelog

## Unreleased — personal batch execution read model

- Add Project-authorized, no-store Batch API 0.3.0 execution observation for context-policy batches: exact immutable template slots, allocated request IDs, separate attempt/outcome/stop and accounting state in one SQL snapshot. Preserve legacy metadata reads, reject inconsistent history, and never expose inputs/secrets or infer retry/admission from absent records.
- No migration, model/account request, credential/inventory consumption, deployment, release or stage-exit change; Web/Deploy and final real acceptance remain subsequent gates.

## Unreleased — controlled personal live runtime

- Connect an independent default-off, exact synthetic Project/batch live-request entry to trusted OIDC dispatch, one shared worker deadline, version-bound credential use and independent usage/result persistence; preserve the original synthetic route and public deny-by-default constructors.
- Append V16 immutable context-policy template request slots, precise conflict handling, bounded credential metadata/intent audit, lazy fixed-host client resources and finite cancellation/shutdown checks. Settings API 0.3.0 reports local controlled availability without probing the provider; live API 0.1.0 accepts only batch/template IDs.
- Keep real calls, deployment and stage closure outside this implementation. Web/Deploy engineering and CI, concrete two-call bindings, authorized synthetic real acceptance, final rollback, artifacts and the unique report remain required.

## Unreleased — test clock isolation

- Isolate all shared mutable-clock Agent integration contexts from the wall-clock test database. Add a database-boundary regression while preserving recovery scheduling, timeout/no-retry assertions and production behavior.

## Unreleased — personal context-cost policy

- Add explicit ADR-0014 context-based reservation (316,373 microUSD), acknowledgement 0.3.0, batch 0.2.0 and metadata APIs 0.2.0 without upgrading legacy approvals, 4,200 reservations, schemas or accounting history.
- Append Agent V15 policy constraints and immutable batch stops; settle independently billable input above the 8,000 post-call threshold, stop on failure/unknown, and require validated explanation success plus settlement before the summary. Fixed request bytes/output, daily limits and default-closed egress remain unchanged; no model/account calls, deployment, release or stage-exit claim.

- Agent：增加默认拒绝且未装配的独立 live 领取/审计、提交后调度与单次 OIDC/trace 作用域；不保留 Token，不启用真实调用、Web 或部署，公共契约和迁移不变。

## Unreleased — 4H local synthetic acceptance

- Add a network-free deterministic adapter guarded by two local profiles and an explicit synthetic flag; default and real-egress behavior remain disabled.
- Add profile/flag, fixed-output and unknown-scenario tests without changing contracts, dependencies or migrations.

所有重要变更记录在此文件。版本遵循语义化版本；项目开发期从 `0.x.y` 开始。

## [Unreleased]

### Added

- Agent 数据库等待与独立清理：原单调预算驱动只读/预留/复核/结果事务，事务级 PostgreSQL SQL/锁超时；费用和失败记录使用独立有限清理并恢复中断。真实临时数据库覆盖取消、回滚、LOCAL 配置不泄漏及迟到连接零动作；无公共契约、迁移、依赖、出口装配或部署变化，不宣称任意网络提交/文件 I/O 可强杀。

- 有界凭据消费等待：所有槽位操作复用一把锁，消费可中断/限时，原执行预算经过锁外意图审计与存储不续期；核心拒绝旧无界消费回退，迟到清理失败保留可信 usage。仅假凭据/合成回归，无迁移、依赖、公开 API、外发装配或部署；不宣称全链路硬取消或阶段完成。

- 真实请求关联与内部创建基础：追加不可变 V14、同 Project 外键、终态模板复制、当前公开资源核验及请求/关联/审计原子提交。费用预留/发送必须匹配关联；旧假模型 worker 与领取 CAS 拒绝 live 或已关联请求。覆盖迁移保留、幂等和隔离回归，无 HTTP 创建入口、调度、Key 消费、模型调用、运行部署、公共契约或依赖变更。

- 版本绑定的加密凭据内部消费基础：同一缓存存储核对当前版本，替换/删除与最终回调串行，拒绝重入；调用前领域/费用检查和意图审计移至凭据锁外。新增未装配桥接与旧版本、并发、失败关闭回归，无真实客户端、调用、运行栈、公开接口、迁移或依赖变更。

- 默认关闭、未注册的 DeepSeek 真实响应执行核心：唯一执行者、锁后发送复核、公开资源精确核验、独立合法用量结算与结果/审计事务；补充重放预检、结果锁等待期限及凭据追溯字段的合成回归。无真实调用、运行栈变更、公开接口、迁移或新依赖；生产取消和接线尚未完成。

- 受控合成来源清单与 Batch 元数据 API 0.1.0：鉴权先于解析、严格有界 JSON、no-store、不可变来源证明及独立 V13；变更/撤回阻止新预留，旧账本保留。默认关闭，无真实调用、运行栈更新或 Web 消费。

- 合成真实验收批次与内部费用账本基础：V12 独立持久化、受控来源核对默认拒绝、三层预留、一次尝试、缓存 Token 结算、未知费用保留与不可变历史；无 HTTP/Web 入口、真实网络调用或既有编排变化。

- 个人 Project Agent 设置和不可变风险确认 API（0.1.0 / 输入 0.2.0）：owner + Project Maintainer、If-Match 行锁、凭据版本/短有效期、幂等撤销、失败审计及独立 V11。没有批次/费用授权或真实模型出口，扫描/门禁与旧迁移不变。

- ADR-0013 个人只写 DeepSeek 凭据 API 与 AES-256-GCM 持久化；默认关闭、owner/Origin 防护，无真实模型调用或自动启用。

- DeepSeek 未装配传输基础：固定 HTTPS、禁止代理/重定向、响应上限、取消与无重试的假 HTTP 回归；有界 Secret 文件读取和价格快照计算。无真实调用、模型 Bean、依赖或迁移；运行授权与持久化账本尚未接入。

- 个人 DeepSeek 接入设计、启用记录提案 Schema 和无网络 Responses codec 回归；不注册真实适配器，不新增依赖/迁移，不启用外发。后续设置、Secret/HTTP、额度结算及真实验收仍未完成。

- 4G：遗留 Agent 请求的有界恢复、不可信用量与追溯元数据校验；并发额度、撤权、审计脱敏和门禁不变的合成测试，无真实外发或迁移改写。

- Web Agent 4F 的只读补充：公开可信当前 PR 修订 ID，按版本 ID 读取当前获授权的不可变文档。

## [0.4.0] - 2026-09-27

### Added

- 持续治理：不可变基线、稳定逻辑指纹、质量门禁、有期限且可审计的例外。
- GitHub 签名 Webhook、幂等 CI 报告提交和显式 `0/2/64/70` 退出码契约。
- 只读 PR 修订差异，独立于基线门禁分类；Flyway V7 追加可信 head 历史。

### Security

- 拒绝跨 Project 访问、Webhook 重放和乱序事件；保留失败写入审计。

## [0.3.0] - 2026-09-22

### Added

- 初始化仓库治理、协作和质量基线。
- 采用 Apache License 2.0，并在 CI 中固定标准许可证校验和。
- 增加 M2 Platform 骨架与 Project 最小垂直切片 Technical Design。
- 初始化 Java 21/Spring Boot 构建、默认拒绝的安全边界、外部化配置和 Actuator 健康探针。
- 实现 M2 首个 Platform 切片：Flyway/PostgreSQL、数据库 readiness、Project 创建/成员查询、统一错误、traceId 与最小审计。
- 增加正式 OIDC JWT Resource Server 适配器，校验 issuer、audience、UUID subject 与 scope 权限。
- 增加 Project 分页列表、乐观锁修改/删除，以及带最后维护者保护的成员增改删。
- 增加 Repository 受控相对路径注册、不可变 RuleSetVersion 与 Scanner `validate-rules` 适配器。
- 增加 PostgreSQL ScanJob 状态机、幂等提交、租约/attempt token、取消和文件邮箱 Runner 协议。
- 固定并重新验证 Scanner Result Schema `0.1.0`，保存原始报告 SHA-256 并规范化 Finding/Evidence。
- 增加 Finding 误报/风险接受处置、乐观锁、追加历史与审计。
- 扩展 Platform OpenAPI v1 并将应用版本推进至 `0.3.0-SNAPSHOT`。
