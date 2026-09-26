package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.BaselineVersionView;
import io.github.aiarchguard.platform.governance.ClassifiedFinding;
import io.github.aiarchguard.platform.governance.ComparisonView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BaselineStore {
    BaselineScope lockOrCreate(UUID projectId, UUID repositoryId, String branch, UUID ruleSetVersionId);
    Optional<BaselineScope> scope(UUID projectId, UUID repositoryId, String branch, UUID ruleSetVersionId);
    Optional<BaselineVersionView> byJob(UUID scopeId, UUID jobId);
    Optional<BaselineVersionView> version(UUID scopeId, UUID versionId);
    List<BaselineVersionView> versions(UUID scopeId);
    BaselineVersionView insertVersion(BaselineScope scope, UUID jobId, String commitSha, String reportSha,
                                      String algorithm, UUID actorId, Instant now, List<FindingSnapshot> findings);
    BaselineVersionView select(BaselineScope scope, UUID versionId, UUID actorId, Instant now);
    List<FindingSnapshot> findings(UUID baselineVersionId);
    Optional<ComparisonView> comparison(UUID baselineVersionId, UUID candidateJobId);
    Optional<ComparisonView> findComparison(UUID projectId, UUID repositoryId, UUID comparisonId);
    ComparisonView insertComparison(UUID baselineVersionId, UUID candidateJobId, String reportSha,
                                    String algorithm, Instant now, List<ClassifiedFinding> findings);
}
