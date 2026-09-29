package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import java.util.List;
import java.util.UUID;

public record AgentSnapshot(AgentRequestView view, AgentModelPort.ModelInput input, List<Candidate> candidates) {
    public record Candidate(String citationId, String source, UUID projectId, UUID scanJobId,
            String reportSha256, UUID evidenceId, UUID documentVersionId, String contentSha256,
            Integer fragmentIndex, String fragmentSha256, String label) {}
}
