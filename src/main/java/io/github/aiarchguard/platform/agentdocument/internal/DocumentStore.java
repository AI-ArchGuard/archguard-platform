package io.github.aiarchguard.platform.agentdocument.internal;

import io.github.aiarchguard.platform.agentdocument.DocumentFragmentView;
import io.github.aiarchguard.platform.agentdocument.DocumentPage;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionView;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionPage;
import io.github.aiarchguard.platform.agentdocument.internal.domain.DocumentContent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentStore {
    boolean claimUpload(UUID projectId, String key, String requestSha256);
    Optional<UploadRequest> findUpload(UUID projectId, String key);
    void completeUpload(UUID projectId, String key, UUID versionId);
    UUID lockOrCreateDocument(UUID projectId, String documentKey, UUID actorId, Instant now);
    int nextVersionNumber(UUID documentId);
    void insertVersion(UUID versionId, UUID documentId, UUID projectId, int number, String mediaType,
                       DocumentContent content, UUID actorId, Instant now);
    void insertFragments(UUID versionId, List<DocumentContent.Fragment> fragments);
    Optional<DocumentVersionView> findVersion(UUID projectId, UUID documentId, UUID versionId);
    Optional<DocumentVersionView> findVersionById(UUID projectId, UUID versionId);
    DocumentPage list(UUID projectId, int page, int size);
    DocumentVersionPage listVersions(UUID projectId, UUID documentId, int page, int size);
    int countVersions(UUID projectId, List<UUID> versionIds);
    List<DocumentFragmentView> search(UUID projectId, List<UUID> versionIds, String query, int limit);
    boolean hasDocuments(UUID projectId);

    record UploadRequest(String requestSha256, UUID versionId) {}
}
