package io.github.aiarchguard.platform.agent;

import java.util.List;
import java.util.UUID;

public record CreateAgentRequest(String purpose, UUID scanJobId, String reportSha256,
        UUID prHeadRevisionId, List<UUID> findingIds, List<UUID> documentVersionIds) {}
