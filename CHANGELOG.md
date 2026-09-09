# Changelog

所有重要变更记录在此文件。版本遵循语义化版本；项目开发期从 `0.x.y` 开始。

## [Unreleased]

### Added

- 初始化仓库治理、协作和质量基线。
- 增加 M2 Platform 骨架与 Project 最小垂直切片 Technical Design。
- 初始化 Java 21/Spring Boot 构建、默认拒绝的安全边界、外部化配置和 Actuator 健康探针。
- 实现 M2 首个 Platform 切片：Flyway/PostgreSQL、数据库 readiness、Project 创建/成员查询、统一错误、traceId 与最小审计。
- 增加正式 OIDC JWT Resource Server 适配器，校验 issuer、audience、UUID subject 与 scope 权限。
- 增加 Project 分页列表、乐观锁修改/删除，以及带最后维护者保护的成员增改删。
