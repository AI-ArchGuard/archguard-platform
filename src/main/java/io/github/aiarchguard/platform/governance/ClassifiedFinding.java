package io.github.aiarchguard.platform.governance;

/**
 * 比较结果中的轻量 Finding 快照，供门禁计算与只读展示使用，不携带源码或完整报告。
 *
 * @param classification 相对于本次参考集合的分类
 * @param fingerprint Platform 逻辑指纹，用于跨扫描集合关联，不是 Scanner Finding ID
 * @param payloadSha256 规范身份载荷的摘要，用于识别同指纹下不一致的载荷
 * @param ruleId 确定性规则身份，也是规则范围例外的匹配对象
 * @param ruleVersion 报告中该规则的版本，与 RuleSetVersion ID 含义不同
 * @param severity NEW/EXISTING 取候选严重性，RESOLVED 取参考快照严重性
 * @param scannerFindingId 用于追溯来源报告内的 Finding，不能充当跨扫描身份
 */
public record ClassifiedFinding(Classification classification, String fingerprint, String payloadSha256,
                                String ruleId, String ruleVersion, String severity, String scannerFindingId) { }
