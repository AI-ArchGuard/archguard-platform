# Changelog

所有重要变更记录在此文件。版本遵循语义化版本；项目开发期从 `0.x.y` 开始。

## [Unreleased]

### Added

- 初始化仓库治理、协作和质量基线。
- 采用 Apache License 2.0，并在 CI 中固定标准许可证校验和。
- 增加 M2 Platform 骨架与 Project 最小垂直切片 Technical Design。
- 初始化 Java 21/Spring Boot 构建、默认拒绝的安全边界、外部化配置和 Actuator 健康探针。
- 实现 M2 首个 Platform 切片：Flyway/PostgreSQL、数据库 readiness、Project 创建/成员查询、统一错误、traceId 与最小审计。
