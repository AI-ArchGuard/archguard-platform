package io.github.aiarchguard.platform.finding;

/**
 * 单次扫描 Finding 的人工处置：待处理、认定误报或接受风险；新导入的 Finding 从 OPEN 开始。
 * 此状态不改写 Scanner 的确定性报告，也不等同于跨扫描继承的门禁例外。
 */
public enum FindingDisposition { OPEN, FALSE_POSITIVE, RISK_ACCEPTED }
