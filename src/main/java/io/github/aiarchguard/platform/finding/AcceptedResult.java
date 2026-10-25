package io.github.aiarchguard.platform.finding;

/**
 * 报告通过校验且规范化数据写入后的回执，供 ScanJob 保存原始报告及完成任务时使用。
 * sha256 对应传入报告的原始字节；scannerVersion 来自调用方参数，schemaVersion 是消费器固定版本。
 */
public record AcceptedResult(String sha256, String scannerVersion, String schemaVersion) { }
