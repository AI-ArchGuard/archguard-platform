package io.github.aiarchguard.platform.agent;

/** 返回服务端计算的清单摘要与模板数，便于批准时精确绑定；摘要一致不能单独证明数据来源为合成。 */
public record LiveBatchPreviewView(String schemaVersion, String manifestSha256, int templateCount) { }
