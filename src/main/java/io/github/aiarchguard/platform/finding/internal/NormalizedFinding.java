package io.github.aiarchguard.platform.finding.internal;

import io.github.aiarchguard.platform.finding.FindingView;
import java.util.List;
import java.util.UUID;

public record NormalizedFinding(UUID id, UUID projectId, UUID jobId, String scannerId, String fingerprint,
                                String ruleId, String ruleVersion, String severity, String subjectId,
                                String message, FindingView.SourceLocation location, List<UUID> evidenceIds) { }
