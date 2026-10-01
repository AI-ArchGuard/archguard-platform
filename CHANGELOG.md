# Changelog

所有重要变更记录在此文件。版本遵循语义化版本；项目开发期从 `0.x.y` 开始。

## [Unreleased]

### Added

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
