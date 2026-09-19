package io.github.aiarchguard.platform.scanjob;

import java.util.List;
import java.util.UUID;

public interface ScanJobOperations {
    ScanJobView submit(UUID projectId, UUID repositoryId, UUID ruleSetVersionId, String idempotencyKey);
    ScanJobView get(UUID projectId, UUID jobId);
    List<ScanJobView> list(UUID projectId, int page, int size);
    ScanJobView cancel(UUID projectId, UUID jobId);
    ScanJobResult result(UUID projectId, UUID jobId);
}
