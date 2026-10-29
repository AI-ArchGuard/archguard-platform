package io.github.aiarchguard.platform.agent;

import java.util.List;

/**
 * 模型调用的不可信端口，供原合成请求流程使用。适配器须显式声明 syntheticOnly 才能进入该流程；
 * 真实调用另走有独立账本的执行链。
 */
public interface AgentModelPort {
    /** 默认拒绝把端口认作合成实现；必须由适配器显式声明，防止真实模型借用合成预算路径。 */
    default boolean syntheticOnly() { return false; }
    boolean available();
    /** 输入已由应用层最小化，但输出、用量和追溯标识仍不可信；worker 必须分别校验后才能保存。 */
    ModelResponse explain(ModelInput input) throws Exception;

    record ModelInput(String purpose, String schemaVersion, String promptVersion, String findingRef,
            String ruleId, String ruleVersion, String severity, String message, String subject,
            List<EvidenceInput> evidence, List<DocumentInput> documents, List<FindingInput> findings) {}
    record FindingInput(String findingRef, String ruleId, String ruleVersion, String severity,
            String message, String subject, List<EvidenceInput> evidence) {}
    record EvidenceInput(String citationId, String kind, String summary, Integer startLine, Integer endLine) {}
    record DocumentInput(String citationId, String contentSha256, String excerpt) {}
    record ModelResponse(String rawJson, int inputTokens, int outputTokens, long latencyMs,
            String providerResponseId, String actualModelId) {}
}
