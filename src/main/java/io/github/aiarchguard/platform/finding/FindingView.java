package io.github.aiarchguard.platform.finding;

import java.util.List;
import java.util.UUID;

public record FindingView(UUID id, UUID projectId, UUID jobId, String scannerFindingId, String fingerprint,
                          String ruleId, String ruleVersion, String severity, String subjectId, String message,
                          SourceLocation location, FindingDisposition disposition, long version,
                          List<UUID> evidenceIds) {
    public record SourceLocation(String path, int startLine, int startColumn, int endLine, int endColumn) { }
}
