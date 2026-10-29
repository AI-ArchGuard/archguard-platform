package io.github.aiarchguard.platform.governance;

/**
 * 门禁 ERROR 的稳定类别，供 CI 区分修正配置与排查执行/报告契约：CONFIGURATION 对应 64，
 * EXECUTION_OR_CONTRACT 对应 70。该类别不把错误转成 PASS，也不等同于 HTTP 响应状态。
 */
public enum GateErrorKind { CONFIGURATION, EXECUTION_OR_CONTRACT }
