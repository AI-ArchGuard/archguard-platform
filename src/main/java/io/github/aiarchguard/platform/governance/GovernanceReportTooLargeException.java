package io.github.aiarchguard.platform.governance;

/**
 * 报告提交契约发现原始报告超过 50 MiB 时抛出的边界异常，HTTP 层返回 413。
 * 这是报告字节大小限制，和 Webhook 入口自己的正文上限分开。
 */
public class GovernanceReportTooLargeException extends RuntimeException { }
