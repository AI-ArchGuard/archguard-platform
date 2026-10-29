package io.github.aiarchguard.platform.governance;

/**
 * 治理门禁结果：PASS 表示策略允许，FAIL 表示存在阻断问题，ERROR 表示无法得出正常策略结论。
 * CI 分别消费 0、2、64/70；报告已接收或任务已执行成功都不等于门禁 PASS。
 */
public enum GateOutcome { PASS, FAIL, ERROR }
