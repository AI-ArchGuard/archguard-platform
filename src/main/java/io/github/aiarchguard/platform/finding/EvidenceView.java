package io.github.aiarchguard.platform.finding;

import java.util.UUID;

public record EvidenceView(UUID id, UUID projectId, UUID jobId, String scannerEvidenceId, String kind,
                           String summary, FindingView.SourceLocation location) {
}
