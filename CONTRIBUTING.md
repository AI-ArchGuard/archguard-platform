# Contributing

## 开始之前

1. 阅读工作区和本仓库的 `AGENTS.md`、本仓库 `README.md`，以及 [ArchGuard 工程规范](https://github.com/AI-ArchGuard/archguard-docs/tree/main/development)。
2. 创建或关联 Issue，明确范围、非目标、验收标准、风险和测试策略。
3. 检查工作树，不覆盖或混入无关修改。
4. 普通功能使用 Feature Spec；中高风险设计和架构决策分别补充 Technical Design 与 ADR。

## 开发约定

- 一次只解决一个明确问题，优先交付端到端最小切片。
- 缺陷修复先增加可复现测试。
- 新增依赖要记录用途、许可证、维护状态、替代方案和替换成本。
- 不提交密钥、Token、密码、真实客户源码、个人数据或生成的本地配置。
- 提交消息遵循 Conventional Commits，例如 `feat(platform): add project registration`。

## 验证与 Pull Request

- 先运行最小相关测试，再运行仓库 README 和 `AGENTS.md` 列出的完整质量检查。
- 未执行的检查必须在 PR 中明确标注。
- PR 关联 Issue，说明修改、影响、测试证据、迁移、兼容性、风险和回滚。
- 默认使用 Squash Merge；不得绕过失败的 CI 或直接推送默认分支。

## 许可证

除非另有明确书面声明，提交到本仓库的贡献按 [Apache License 2.0](LICENSE) 提供。
