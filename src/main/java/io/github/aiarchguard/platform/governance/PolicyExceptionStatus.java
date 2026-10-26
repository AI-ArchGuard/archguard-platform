package io.github.aiarchguard.platform.governance;

/**
 * 按观察时间派生的例外展示状态，不是后台定时更新的生命周期列。
 * 已到撤销时刻优先为 REVOKED；否则按生效/到期时刻判定 PENDING、ACTIVE、EXPIRED。
 * 门禁仍按自己的 evaluatedAt 判断有效性，当前展示状态不会改写历史结论。
 */
public enum PolicyExceptionStatus { PENDING, ACTIVE, EXPIRED, REVOKED }
