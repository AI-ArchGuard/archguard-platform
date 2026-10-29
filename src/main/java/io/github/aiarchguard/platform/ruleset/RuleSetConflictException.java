package io.github.aiarchguard.platform.ruleset;

/**
 * 创建规则集元数据发生唯一键冲突时使用的业务异常，对外映射为 key 已占用的 HTTP 409。
 * 当前创建 YAML 版本的流程没有把版本号唯一约束冲突转换为此异常或自动重试。
 */
public final class RuleSetConflictException extends RuntimeException {
}
