package io.github.aiarchguard.platform.agentdocument.persistence;

import io.github.aiarchguard.platform.agentdocument.DocumentFragmentView;
import io.github.aiarchguard.platform.agentdocument.DocumentPage;
import io.github.aiarchguard.platform.agentdocument.DocumentNotFoundException;
import io.github.aiarchguard.platform.agentdocument.DocumentSummary;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionPage;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionSummary;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionView;
import io.github.aiarchguard.platform.agentdocument.internal.DocumentStore;
import io.github.aiarchguard.platform.agentdocument.internal.domain.DocumentContent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentStore implements DocumentStore {
    private final JdbcClient jdbc;

    public JdbcDocumentStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean claimUpload(UUID projectId, String key, String requestSha256) {
        return jdbc.sql("""
            INSERT INTO agent_document.upload_requests (project_id, idempotency_key, request_sha256)
            VALUES (:project, :key, :sha)
            ON CONFLICT (project_id, idempotency_key) DO NOTHING
            """).param("project", projectId).param("key", key).param("sha", requestSha256).update() == 1;
    }

    @Override
    public Optional<UploadRequest> findUpload(UUID projectId, String key) {
        return jdbc.sql("""
            SELECT request_sha256, version_id FROM agent_document.upload_requests
            WHERE project_id=:project AND idempotency_key=:key
            """).param("project", projectId).param("key", key)
            .query((rs, row) -> new UploadRequest(rs.getString("request_sha256"), rs.getObject("version_id", UUID.class)))
            .optional();
    }

    @Override
    public void completeUpload(UUID projectId, String key, UUID versionId) {
        int updated = jdbc.sql("""
            UPDATE agent_document.upload_requests SET version_id=:version
            WHERE project_id=:project AND idempotency_key=:key AND version_id IS NULL
            """).param("version", versionId).param("project", projectId).param("key", key).update();
        if (updated != 1) throw new IllegalStateException("Upload slot was not available");
    }

    @Override
    public UUID lockOrCreateDocument(UUID projectId, String documentKey, UUID actorId, Instant now) {
        jdbc.sql("""
            INSERT INTO agent_document.documents (id, project_id, document_key, created_by, created_at)
            VALUES (:id, :project, :key, :actor, :created)
            ON CONFLICT (project_id, document_key) DO NOTHING
            """).param("id", UUID.randomUUID()).param("project", projectId).param("key", documentKey)
            .param("actor", actorId).param("created", Timestamp.from(now)).update();
        return jdbc.sql("""
            SELECT id FROM agent_document.documents WHERE project_id=:project AND document_key=:key FOR UPDATE
            """).param("project", projectId).param("key", documentKey).query(UUID.class).single();
    }

    @Override
    public int nextVersionNumber(UUID documentId) {
        return jdbc.sql("""
            SELECT COALESCE(MAX(version_number), 0) + 1 FROM agent_document.document_versions WHERE document_id=:id
            """).param("id", documentId).query(Integer.class).single();
    }

    @Override
    public void insertVersion(UUID versionId, UUID documentId, UUID projectId, int number, String mediaType,
            DocumentContent content, UUID actorId, Instant now) {
        jdbc.sql("""
            INSERT INTO agent_document.document_versions
                (id, document_id, project_id, version_number, media_type, content_text, content_sha256,
                 byte_size, fragment_count, created_by, created_at)
            VALUES (:id, :document, :project, :number, :type, :content, :sha, :bytes, :fragments, :actor, :created)
            """).param("id", versionId).param("document", documentId).param("project", projectId)
            .param("number", number).param("type", mediaType).param("content", content.text())
            .param("sha", content.sha256()).param("bytes", content.byteSize())
            .param("fragments", content.fragments().size()).param("actor", actorId)
            .param("created", Timestamp.from(now)).update();
    }

    @Override
    public void insertFragments(UUID versionId, List<DocumentContent.Fragment> fragments) {
        for (DocumentContent.Fragment fragment : fragments) {
            jdbc.sql("""
                INSERT INTO agent_document.document_fragments
                    (version_id, fragment_index, start_offset, content_text, fragment_sha256)
                VALUES (:version, :index, :offset, :content, :sha)
                """).param("version", versionId).param("index", fragment.index())
                .param("offset", fragment.startOffset()).param("content", fragment.content())
                .param("sha", fragment.sha256()).update();
        }
    }

    @Override
    public Optional<DocumentVersionView> findVersion(UUID projectId, UUID documentId, UUID versionId) {
        return jdbc.sql("""
            SELECT v.*, d.document_key FROM agent_document.document_versions v
            JOIN agent_document.documents d ON d.id=v.document_id AND d.project_id=v.project_id
            WHERE v.project_id=:project AND v.document_id=:document AND v.id=:version
            """).param("project", projectId).param("document", documentId).param("version", versionId)
            .query(JdbcDocumentStore::version).optional();
    }

    @Override
    public Optional<DocumentVersionView> findVersionById(UUID projectId, UUID versionId) {
        return jdbc.sql("""
            SELECT v.*, d.document_key FROM agent_document.document_versions v
            JOIN agent_document.documents d ON d.id=v.document_id AND d.project_id=v.project_id
            WHERE v.project_id=:project AND v.id=:version
            """).param("project", projectId).param("version", versionId)
            .query(JdbcDocumentStore::version).optional();
    }

    @Override
    public DocumentPage list(UUID projectId, int page, int size) {
        long total = jdbc.sql("SELECT count(*) FROM agent_document.documents WHERE project_id=:project")
            .param("project", projectId).query(Long.class).single();
        List<DocumentSummary> items = jdbc.sql("""
            SELECT d.id, d.document_key, d.created_by, d.created_at,
                   (SELECT MAX(v.version_number) FROM agent_document.document_versions v
                    WHERE v.document_id=d.id) AS latest_version_number
            FROM agent_document.documents d WHERE d.project_id=:project
            ORDER BY d.created_at DESC, d.id DESC LIMIT :size OFFSET :offset
            """).param("project", projectId).param("size", size).param("offset", (long) page * size)
            .query((rs, row) -> new DocumentSummary(rs.getObject("id", UUID.class), rs.getString("document_key"),
                rs.getInt("latest_version_number"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant())).list();
        return new DocumentPage(items, page, size, total);
    }

    @Override
    public DocumentVersionPage listVersions(UUID projectId, UUID documentId, int page, int size) {
        boolean exists = jdbc.sql("""
            SELECT EXISTS (SELECT 1 FROM agent_document.documents WHERE project_id=:project AND id=:document)
            """).param("project", projectId).param("document", documentId).query(Boolean.class).single();
        if (!exists) throw new DocumentNotFoundException();
        long total = jdbc.sql("""
            SELECT count(*) FROM agent_document.document_versions
            WHERE project_id=:project AND document_id=:document
            """).param("project", projectId).param("document", documentId).query(Long.class).single();
        List<DocumentVersionSummary> items = jdbc.sql("""
            SELECT id, version_number, media_type, content_sha256, byte_size, fragment_count, created_by, created_at
            FROM agent_document.document_versions
            WHERE project_id=:project AND document_id=:document
            ORDER BY version_number DESC LIMIT :size OFFSET :offset
            """).param("project", projectId).param("document", documentId)
            .param("size", size).param("offset", (long) page * size)
            .query((rs, row) -> new DocumentVersionSummary(rs.getObject("id", UUID.class),
                rs.getInt("version_number"), rs.getString("media_type"), rs.getString("content_sha256"),
                rs.getInt("byte_size"), rs.getInt("fragment_count"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant())).list();
        return new DocumentVersionPage(items, page, size, total);
    }

    @Override
    public int countVersions(UUID projectId, List<UUID> versionIds) {
        return jdbc.sql("""
            SELECT count(*) FROM agent_document.document_versions WHERE project_id=:project AND id IN (:versions)
            """).param("project", projectId).param("versions", versionIds).query(Integer.class).single();
    }

    @Override
    public List<DocumentFragmentView> search(UUID projectId, List<UUID> versionIds, String query, int limit) {
        return jdbc.sql("""
            SELECT v.project_id, v.id AS document_version_id, v.content_sha256, f.fragment_index,
                   f.start_offset, f.fragment_sha256, f.content_text
            FROM agent_document.document_fragments f
            JOIN agent_document.document_versions v ON v.id=f.version_id
            WHERE v.project_id=:project AND v.id IN (:versions)
              AND position(lower(:query) in lower(f.content_text)) > 0
            ORDER BY v.created_at DESC, v.id DESC, f.fragment_index ASC
            LIMIT :limit
            """).param("project", projectId).param("versions", versionIds)
            .param("query", query).param("limit", limit)
            .query((rs, row) -> new DocumentFragmentView(rs.getObject("project_id", UUID.class),
                rs.getObject("document_version_id", UUID.class), rs.getString("content_sha256"),
                rs.getInt("fragment_index"), rs.getInt("start_offset"), rs.getString("fragment_sha256"),
                rs.getString("content_text"))).list();
    }

    @Override
    public boolean hasDocuments(UUID projectId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM agent_document.documents WHERE project_id=:project)")
            .param("project", projectId).query(Boolean.class).single();
    }

    private static DocumentVersionView version(ResultSet rs, int row) throws SQLException {
        return new DocumentVersionView(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
            rs.getObject("project_id", UUID.class), rs.getString("document_key"), rs.getInt("version_number"),
            rs.getString("media_type"), rs.getString("content_text"), rs.getString("content_sha256"),
            rs.getInt("byte_size"), rs.getInt("fragment_count"), rs.getObject("created_by", UUID.class),
            rs.getTimestamp("created_at").toInstant());
    }
}
