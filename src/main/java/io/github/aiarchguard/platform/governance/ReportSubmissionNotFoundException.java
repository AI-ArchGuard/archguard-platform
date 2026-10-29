package io.github.aiarchguard.platform.governance;

/**
 * 在已授权 Project/Repository 中没有指定报告提交记录，HTTP 层返回治理资源 404。
 * 已存在但仍为 RECEIVED 的提交不属于这种缺失情况，查询门禁时应明确返回未完成状态。
 */
public class ReportSubmissionNotFoundException extends RuntimeException { }
