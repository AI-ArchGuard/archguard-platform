package io.github.aiarchguard.platform.finding.internal;

import io.github.aiarchguard.platform.finding.FindingView;
import java.util.UUID;

public record NormalizedEvidence(UUID id, UUID projectId, UUID jobId, String scannerId, String kind,
                                 String summary, FindingView.SourceLocation location) { }
