package io.github.aiarchguard.platform.governance;

public record ClassifiedFinding(Classification classification, String fingerprint, String payloadSha256,
                                String ruleId, String ruleVersion, String severity, String scannerFindingId) { }
