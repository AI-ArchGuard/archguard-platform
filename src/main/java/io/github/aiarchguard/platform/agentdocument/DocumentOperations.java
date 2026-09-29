package io.github.aiarchguard.platform.agentdocument;

import java.util.List;
import java.util.UUID;

public interface DocumentOperations {
    DocumentUploadResult upload(UUID projectId, String documentKey, String idempotencyKey,
                                String mediaType, String filename, byte[] content);
    DocumentPage list(UUID projectId, int page, int size);
    DocumentVersionPage listVersions(UUID projectId, UUID documentId, int page, int size);
    DocumentVersionView getVersion(UUID projectId, UUID documentId, UUID versionId);
    DocumentVersionView getVersionById(UUID projectId, UUID versionId);
    List<DocumentFragmentView> search(UUID projectId, List<UUID> versionIds, String query);
}
