# Changelog

所有重要变更记录在此文件。版本遵循语义化版本；项目开发期从 `0.x.y` 开始。

## [Unreleased]

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
