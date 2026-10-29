package io.github.aiarchguard.platform.governance;

/**
 * 报告提交的持久化处理状态：RECEIVED 表示报告和导入结果已接收、尚未完成门禁关联，
 * COMPLETED 表示已关联确切门禁。它不描述异步线程状态，也不替代 PASS/FAIL/ERROR。
 */
public enum ReportSubmissionStatus { RECEIVED, COMPLETED }
