package io.github.aiarchguard.platform.scanjob;

import java.util.UUID;

/** Accepts a CI-produced Scanner report without scheduling the local mailbox Runner. */
public interface ScanJobImportOperations {
    void importCompleted(UUID projectId, UUID repositoryId, UUID ruleSetVersionId,
                         UUID jobId, String scannerVersion, byte[] report);
}
