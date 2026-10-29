package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import java.util.List;
import java.util.UUID;

/** 内部持久化请求快照：将公开视图、最小模型输入与引用候选映射放在一起。候选保存内部事实定位，模型只看到随机别名。 */
public record AgentSnapshot(AgentRequestView view, AgentModelPort.ModelInput input, List<Candidate> candidates) {
    public record Candidate(String citationId, String source, UUID projectId, UUID scanJobId,
            String reportSha256, UUID evidenceId, UUID documentVersionId, String contentSha256,
            Integer fragmentIndex, String fragmentSha256, String label) {}
}
