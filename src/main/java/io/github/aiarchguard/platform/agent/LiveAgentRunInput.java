package io.github.aiarchguard.platform.agent;

import java.util.UUID;

/** Only immutable server-side selection identifiers, never model input or credentials. */
public record LiveAgentRunInput(UUID batchId, UUID templateRequestId) { }
