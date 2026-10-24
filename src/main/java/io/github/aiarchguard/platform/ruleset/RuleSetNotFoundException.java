package io.github.aiarchguard.platform.ruleset;

/**
 * 表示规则集或版本不存在于所查询的项目、仓库范围，Web 层将其映射为统一的 HTTP 404。
 * 该异常不替代 Project 成员鉴权；Catalog 的归属查询也可能抛出它。
 */
public final class RuleSetNotFoundException extends RuntimeException {
}
