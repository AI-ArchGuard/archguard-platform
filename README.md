# ArchGuard Platform

ArchGuard 的核心业务平台，初期采用 Java/Spring Boot 模块化单体。

## 当前状态

阶段 0 `v0.1.0-foundation` 正在远端收口。本交付只建立 Apache-2.0 许可证、基础 CI 和阶段边界说明；`main` 尚未初始化业务工程。现有阶段 2 Platform 分支只作为预实现资产保留，在 Scanner `v0.2.0-scanner` 发布前冻结功能扩展。

## 职责

- 管理用户、项目、代码仓库、规则、ADR 元数据、扫描任务和审计。
- 提供版本化 REST API、统一错误结构和业务权限校验。
- 编排扫描工作流并保存业务事实和可追溯结果。
- 维护 identity、project、repository、policy、scan、result、architecture、audit 等候选领域模块边界；模块只在出现真实职责时物化。

## 非职责

- 不实现源码、字节码或依赖图分析；这属于 `archguard-scanner`。
- 不承担 MCP 工具发现、路由或限流；这属于 `archguard-mcp-gateway`。
- 不保存部署密钥，也不把数据库、Redis 或内部端口暴露到公网。
- 初期不拆分为微服务，不为展示技术而引入 Kafka 或 Redis。

## 依赖与契约

- 通过版本化 Scanner 契约集成 `archguard-scanner`，不得依赖其内部类。
- PostgreSQL 是业务事实来源；模块之间通过公开应用接口或事件协作，不直接跨模块写表。
- 跨仓库架构与工程规范以 [archguard-docs](https://github.com/AI-ArchGuard/archguard-docs) 为准。
- 当前阶段与冻结边界以 Docs 的 `product/roadmap.md` 和 ADR-0006 为准；旧 M0–M13 编号只作为历史设计上下文。

## 本地验证

当前基线可执行：

```bash
git diff --check
git status --short
```

阶段 2 正式启用并初始化 Maven Wrapper 后，完整验证命令为 `./mvnw verify`；Windows 使用 `.\mvnw.cmd verify`。在 Wrapper 合入 `main` 前不得声称该命令已可运行。

## 许可证

本仓库采用 [Apache License 2.0](LICENSE)。
