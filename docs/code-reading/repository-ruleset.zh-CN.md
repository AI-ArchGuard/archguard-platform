# Repository 与 RuleSet：源码入口和规则版本如何关联

建议先读 [Project 指南](project-module.zh-CN.md)，理解项目成员授权，再阅读本批的 Repository/RuleSet 注释。这两个模块负责登记扫描输入与规则，不负责 Git 克隆、源码解析或模型调用。

第二批覆盖28个生产 Java 文件和1个路径测试；连同第一批，累计74个 Java 文件。其他业务模块、集成测试和仓库仍按后续批次推进，不代表全项目已经注释完成。

## 建议阅读顺序

1. [RepositoryController](../../src/main/java/io/github/aiarchguard/platform/repository/web/RepositoryController.java)和[RepositoryOperations](../../src/main/java/io/github/aiarchguard/platform/repository/RepositoryOperations.java)：看仓库注册、列表和查询需要什么参数。
2. [RepositoryApplicationService](../../src/main/java/io/github/aiarchguard/platform/repository/internal/RepositoryApplicationService.java)：看授权、输入校验、路径登记、存储及审计如何顺序执行。
3. [ControlledRepositoryPath](../../src/main/java/io/github/aiarchguard/platform/repository/internal/ControlledRepositoryPath.java)与[路径测试](../../src/test/java/io/github/aiarchguard/platform/repository/internal/ControlledRepositoryPathTest.java)：理解“受控本地目录”是什么意思，以及路径校验的实际边界。
4. [RepositoryView](../../src/main/java/io/github/aiarchguard/platform/repository/RepositoryView.java)和[JdbcRepositoryStore](../../src/main/java/io/github/aiarchguard/platform/repository/persistence/JdbcRepositoryStore.java)：把返回字段与持久化记录对应起来。
5. [RuleSetController](../../src/main/java/io/github/aiarchguard/platform/ruleset/web/RuleSetController.java)、[RuleSetOperations](../../src/main/java/io/github/aiarchguard/platform/ruleset/RuleSetOperations.java)和[RuleSetApplicationService](../../src/main/java/io/github/aiarchguard/platform/ruleset/internal/RuleSetApplicationService.java)：区分创建规则集与追加规则版本。
6. [RuleValidationPort](../../src/main/java/io/github/aiarchguard/platform/ruleset/internal/RuleValidationPort.java)与[ScannerProcessRuleValidator](../../src/main/java/io/github/aiarchguard/platform/ruleset/infrastructure/ScannerProcessRuleValidator.java)：看为什么通过独立 Scanner CLI 校验，而不是导入 Scanner 的内部 Java 类型。
7. [RuleSetVersionView](../../src/main/java/io/github/aiarchguard/platform/ruleset/RuleSetVersionView.java)和[JdbcRuleSetStore](../../src/main/java/io/github/aiarchguard/platform/ruleset/persistence/JdbcRuleSetStore.java)：理解版本号、原始 YAML、摘要及兼容版本的保存方式。

## Repository 不是 Git 托管服务

这里的 Repository 表示“某个 Project 已登记的一份扫描输入”。注册参数 `mountPath` 是部署者配置的 source root 下的相对路径，不是任意主机路径、Git URL 或上传内容。当前代码不会因为调用注册接口而克隆远端仓库。

应用服务先要求 Project 维护权限，再检查 key/name 和本地目录。成功后生成仓库 UUID，以及形如 `platform:<projectId>:<repositoryId>` 的 `scannerIdentity`。后者是 Scanner 契约中识别该扫描对象的逻辑身份，不是登录账号、访问凭据或目录名称。

Controller 直接返回 `RepositoryView`，RuleSet 的 Controller 也直接返回对应公开视图；不要把 Project 中“单独转换 HTTP 响应 DTO”的写法误认为这里已经额外实现了相同响应类。二者仍没有把 JDBC 行对象直接交给调用者。

## 路径检查要分两层理解

首先把分隔符统一并规范化相对路径，检查绝对路径、向上越界和组合后的根目录范围；然后解析真实路径，检查目标仍在真实 source root 下、相对路径各段没有符号链接，以及目标确实是目录。

这些是注册时对文件系统的观察，不会把目录打开并一直锁住，也不保证消除检查后替换目录的竞态。源码文件的后续访问仍应遵守 Scanner/Runner 自身边界。不要把此实现解释为覆盖任意平台所有链接形式的完整文件系统沙箱。

两个现有路径测试只验证真实目录的相对路径规范化，以及绝对路径、遍历、缺失路径和普通文件的拒绝。测试字符串中的 `class Source {}` 是合成文件内容，本次没有向其中插入中文注释或改变字节。

## 用例接口与 Catalog 的区别

HTTP 创建和查询经过 `RepositoryOperations` / `RuleSetOperations` 的应用实现，执行对应的 Project 维护者或查看者授权。

[RepositoryCatalog](../../src/main/java/io/github/aiarchguard/platform/repository/RepositoryCatalog.java)和[RuleSetCatalog](../../src/main/java/io/github/aiarchguard/platform/ruleset/RuleSetCatalog.java)给内部调用方提供范围受约束的已登记对象/版本查询。`requireRegistered` 和 `requireVersion` 不会仅凭传入 UUID 就认证调用者，也不各自执行 Project 成员授权。读取调用方时，应继续确认其在调用 Catalog 前后如何完成自身的权限与业务检查。

## RuleSet 与 RuleSetVersion 不是同一层东西

RuleSet 是归属于 Project 和 Repository 的规则集登记信息，例如 key、展示名和创建者。RuleSetVersion 才保存某次提交的 YAML、内容 SHA-256、规则版本号以及 Scanner/Schema 兼容版本。

新增版本先检查项目维护权限、仓库归属、规则集存在性和 UTF-8 字节大小，再用当前的 identity 匹配逻辑做前置检查，最后调用 Scanner 校验器。这里的正则提取不是完整 YAML 解析器；不能仅凭 identity 字段通过就推断整份配置合法。

摘要来自提交 YAML 的原始 UTF-8 字节，不是先把 YAML 重新序列化成某种规范格式后再计算。因此只改变空白或注释，也可能得到不同内容摘要。注释任务不会改这些作为运行数据的 YAML 字符串。

当前接口只追加规则版本，没有修改旧版本正文的入口。版本号的存储实现是查询当前最大值加一，再插入受唯一约束保护的新记录；这不等于全程加锁的序号分配，也没有在此处实现并发冲突自动重试。

## Scanner 校验进程的实际边界

`ScannerProcessRuleValidator` 把 YAML 写入临时目录，使用固定参数启动 `java -jar ... validate-rules ...`，不是拼接一条任意 Shell 命令。独立进程边界也让 Platform 不需要依赖 Scanner 的内部类。

进程环境会删除名字命中特定前缀或大写 `TOKEN`、`SECRET`、`PASSWORD` 的变量；这不是完整的环境白名单。输出最多读取4096字节，并替换本次规则文件和 Scanner JAR 的两条已知路径，也不代表任意输出内容都已完成脱敏。

超时分支会请求强制结束该父进程，但不能据此宣称已经证明整个子进程树回收。`finally` 只尽力删除规则文件和临时目录；删除失败可能留下文件，不应读成这里存在已实现的后台重试保证。本批仅如实解释，不修改这些行为。

## 不要套用 Project 的事务结论

前一份指南中的 Project 创建有独立的事务服务。这里的 Repository/RuleSet 应用服务没有为整个注册/版本写入加审计调用声明同样的整体事务。

代码顺序调用存储和审计，并不自动保证审计失败时先前插入的业务记录也一起回滚。阅读时要看实际事务注解和调用边界，不能只看服务类名或架构目标。成员授权、查询范围和数据库唯一约束也不等于跨模块原子操作。

## 验证范围与下一步

本批的路径单测直接覆盖目录输入规则；同时运行既有 [GovernanceApiIntegrationTest](../../src/test/java/io/github/aiarchguard/platform/GovernanceApiIntegrationTest.java)，作为已有仓库/规则版本消费链的回归。后者不是仓库注册和 Scanner 校验进程的完整集成证明，不能据此扩大测试覆盖声明。

所有新增内容都是注释或阅读说明；Java 可执行语法、SQL、YAML、契约、测试断言和已发布迁移不变。接下来继续 ScanJob、Finding 等模块，再推进其他仓库。
