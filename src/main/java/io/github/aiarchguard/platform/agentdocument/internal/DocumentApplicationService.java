package io.github.aiarchguard.platform.agentdocument.internal;

import io.github.aiarchguard.platform.agentdocument.DocumentConflictException;
import io.github.aiarchguard.platform.agentdocument.DocumentFragmentView;
import io.github.aiarchguard.platform.agentdocument.DocumentNotFoundException;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.agentdocument.DocumentPage;
import io.github.aiarchguard.platform.agentdocument.DocumentUploadResult;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionView;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionPage;
import io.github.aiarchguard.platform.agentdocument.InvalidDocumentException;
import io.github.aiarchguard.platform.agentdocument.internal.domain.DocumentContent;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文档应用服务：通过公开项目接口授权，在同一事务内创建版本、片段、幂等结果及审计。 */
@Service
class DocumentApplicationService implements DocumentOperations {
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9-]{2,62}$");
    private static final int MAX_SELECTED_VERSIONS = 10;
    private static final int MAX_RESULTS = 4;
    private final ProjectAuthorization projects;
    private final CurrentActorProvider actors;
    private final DocumentStore store;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final Clock clock;

    DocumentApplicationService(ProjectAuthorization projects, CurrentActorProvider actors, DocumentStore store,
            AuditRecorder audit, TraceIdProvider traceIds, Clock clock) {
        this.projects = projects; this.actors = actors; this.store = store; this.audit = audit;
        this.traceIds = traceIds; this.clock = clock;
    }

    /** 先验证权限和内容，再占用幂等槽；持有文档行锁分配连续版本号。 */
    @Override
    @Transactional
    public DocumentUploadResult upload(UUID projectId, String documentKey, String idempotencyKey,
            String mediaType, String filename, byte[] bytes) {
        projects.requireMaintainer(projectId);
        validateKey(documentKey);
        validateIdempotencyKey(idempotencyKey);
        validateFileType(mediaType, filename);
        DocumentContent content = DocumentContent.parse(bytes);
        String requestHash = DocumentContent.sha256((documentKey + "\n" + mediaType + "\n" + content.sha256())
            .getBytes(StandardCharsets.UTF_8));
        // 唯一槽避免并发重放重复建版本；摘要包含文档 key、媒体类型及原始字节摘要。
        if (!store.claimUpload(projectId, idempotencyKey, requestHash)) {
            DocumentStore.UploadRequest existing = store.findUpload(projectId, idempotencyKey).orElseThrow();
            if (!existing.requestSha256().equals(requestHash)) throw new DocumentConflictException();
            DocumentVersionView version = store.findVersionById(projectId, existing.versionId())
                .orElseThrow(IllegalStateException::new);
            return new DocumentUploadResult(version, true);
        }
        UUID actorId = actors.currentActor().id();
        Instant now = Instant.now(clock);
        UUID documentId = store.lockOrCreateDocument(projectId, documentKey, actorId, now);
        int number = store.nextVersionNumber(documentId);
        UUID versionId = UUID.randomUUID();
        store.insertVersion(versionId, documentId, projectId, number, mediaType, content, actorId, now);
        store.insertFragments(versionId, content.fragments());
        store.completeUpload(projectId, idempotencyKey, versionId);
        // 只审计身份、摘要和计数，不把正文或客户端文件名写入审计。
        audit.record(new AuditEvent(actorId, projectId, "agent_document.upload", AuditResult.SUCCESS,
            traceIds.currentTraceId(), Map.of("documentId", documentId, "versionId", versionId,
                "versionNumber", number, "contentSha256", content.sha256(), "byteSize", content.byteSize(),
                "fragmentCount", content.fragments().size())));
        return new DocumentUploadResult(store.findVersion(projectId, documentId, versionId).orElseThrow(), false);
    }

    /** 授权后读取有界元数据页，列表不返回正文。 */
    @Override
    @Transactional(readOnly = true)
    public DocumentPage list(UUID projectId, int page, int size) {
        // 先验证项目可见性，再检查分页，沿用项目模块对不可见资源的隐藏语义。
        projects.requireViewer(projectId);
        validatePage(page, size);
        return store.list(projectId, page, size);
    }

    /** 读取当前项目内指定文档的历史版本摘要。 */
    @Override
    @Transactional(readOnly = true)
    public DocumentVersionPage listVersions(UUID projectId, UUID documentId, int page, int size) {
        // 持久化查询同时限定项目和文档，版本列表不包含正文。
        projects.requireViewer(projectId);
        validatePage(page, size);
        return store.listVersions(projectId, documentId, page, size);
    }

    /** 解析完整层级中的确切版本，找不到或归属不符统一返回缺失。 */
    @Override
    @Transactional(readOnly = true)
    public DocumentVersionView getVersion(UUID projectId, UUID documentId, UUID versionId) {
        // 精确读取不可变版本，不因新上传而替换引用目标。
        projects.requireViewer(projectId);
        return store.findVersion(projectId, documentId, versionId).orElseThrow(DocumentNotFoundException::new);
    }

    /** 按版本 ID 提供授权后的正文解析，不放宽项目归属校验。 */
    @Override
    @Transactional(readOnly = true)
    public DocumentVersionView getVersionById(UUID projectId, UUID versionId) {
        // 供调用方按版本解析正文，仍必须经过当前项目授权。
        projects.requireViewer(projectId);
        return store.findVersionById(projectId, versionId).orElseThrow(DocumentNotFoundException::new);
    }

    /** 在全部可见的显式版本中做有界检索，候选结果仍需由后续引用使用方验证。 */
    @Override
    @Transactional(readOnly = true)
    public List<DocumentFragmentView> search(UUID projectId, List<UUID> versionIds, String query) {
        // 显式选定版本，禁止重复、空值或无界范围；不自动扩展为全项目搜索。
        projects.requireViewer(projectId);
        if (versionIds == null || versionIds.isEmpty() || versionIds.size() > MAX_SELECTED_VERSIONS
                || versionIds.contains(null) || new HashSet<>(versionIds).size() != versionIds.size()) {
            throw new InvalidDocumentException("Search requires 1–10 distinct document versions");
        }
        if (query == null || query.strip().length() < 2 || query.strip().length() > 120) {
            throw new InvalidDocumentException("Search query must contain 2–120 characters");
        }
        // 任一版本缺失或属于其他项目时整体拒绝，避免以部分命中泄露资源存在性。
        if (store.countVersions(projectId, versionIds) != versionIds.size()) throw new DocumentNotFoundException();
        return store.search(projectId, versionIds, query.strip(), MAX_RESULTS);
    }

    private static void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new InvalidDocumentException("Document key must be a 3–63 character lowercase slug");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new InvalidDocumentException("Invalid page or size");
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.length() < 1 || key.length() > 128 || key.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new InvalidDocumentException("Idempotency-Key must be 1–128 printable ASCII characters");
        }
    }

    /** 文件名只用于类型校验；路径形式、非文本扩展名或媒体类型组合均拒绝。 */
    private static void validateFileType(String type, String filename) {
        if (filename == null || filename.contains("/") || filename.contains("\\")) {
            throw new InvalidDocumentException("A simple document filename is required");
        }
        boolean markdown = "text/markdown".equals(type)
            && (filename.endsWith(".md") || filename.endsWith(".markdown"));
        boolean plain = "text/plain".equals(type) && filename.endsWith(".txt");
        if (!markdown && !plain) throw new InvalidDocumentException("Only Markdown and plain text files are accepted");
    }
}
