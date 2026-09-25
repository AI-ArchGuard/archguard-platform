package io.github.aiarchguard.platform.governance.internal;

public record FindingSnapshot(String fingerprint, String payloadSha256, String ruleId, String ruleVersion,
                              String severity, String scannerFindingId) { }
