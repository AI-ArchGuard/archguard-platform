package io.github.aiarchguard.platform.scanjob.persistence;

import io.github.aiarchguard.platform.scanjob.ScanJobOutcome;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import io.github.aiarchguard.platform.scanjob.internal.ScanJobRecord;
import io.github.aiarchguard.platform.scanjob.internal.ScanJobStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcScanJobStore implements ScanJobStore {
    private final JdbcClient jdbc;
    public JdbcScanJobStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public void insert(ScanJobRecord j) {
        jdbc.sql("""
            INSERT INTO scanjob.scan_jobs
              (id, project_id, repository_id, rule_set_version_id, idempotency_key, request_sha256,
               status, created_by, created_at, attempt, version, report_partial)
            VALUES (:id,:project,:repository,:rules,:key,:hash,:status,:actor,:created,0,0,FALSE)
            """).param("id", j.id()).param("project", j.projectId()).param("repository", j.repositoryId())
            .param("rules", j.ruleSetVersionId()).param("key", j.idempotencyKey()).param("hash", j.requestSha256())
            .param("status", j.status().name()).param("actor", j.createdBy()).param("created", Timestamp.from(j.createdAt()))
            .update();
    }

    @Override public Optional<ScanJobRecord> findByIdempotency(UUID projectId, String key) {
        return jdbc.sql("SELECT * FROM scanjob.scan_jobs WHERE project_id=:project AND idempotency_key=:key")
            .param("project", projectId).param("key", key).query(JdbcScanJobStore::map).optional();
    }
    @Override public Optional<ScanJobRecord> find(UUID projectId, UUID jobId) {
        return jdbc.sql("SELECT * FROM scanjob.scan_jobs WHERE project_id=:project AND id=:id")
            .param("project", projectId).param("id", jobId).query(JdbcScanJobStore::map).optional();
    }
    @Override public Optional<ScanJobRecord> findById(UUID jobId) {
        return jdbc.sql("SELECT * FROM scanjob.scan_jobs WHERE id=:id").param("id", jobId)
            .query(JdbcScanJobStore::map).optional();
    }
    @Override public List<ScanJobRecord> list(UUID projectId, int page, int size) {
        return jdbc.sql("SELECT * FROM scanjob.scan_jobs WHERE project_id=:project ORDER BY created_at DESC,id DESC LIMIT :size OFFSET :offset")
            .param("project", projectId).param("size", size).param("offset", page * size)
            .query(JdbcScanJobStore::map).list();
    }

    @Override
    @Transactional
    public Optional<ScanJobRecord> cancel(UUID projectId, UUID jobId, Instant now) {
        int changed = jdbc.sql("""
            UPDATE scanjob.scan_jobs
            SET status=CASE WHEN status='QUEUED' THEN 'CANCELLED' ELSE 'CANCEL_REQUESTED' END,
                completed_at=CASE WHEN status='QUEUED' THEN :now ELSE completed_at END, version=version+1
            WHERE project_id=:project AND id=:id AND status IN ('QUEUED','RUNNING')
            """).param("now", Timestamp.from(now)).param("project", projectId).param("id", jobId).update();
        if (changed == 0) {
            return find(projectId, jobId);
        }
        return find(projectId, jobId);
    }

    @Override
    @Transactional
    public Optional<ScanJobRecord> claim(Instant now, Instant leaseUntil, UUID attemptToken, int maxAttempts) {
        Optional<UUID> candidate = jdbc.sql("""
            SELECT id FROM scanjob.scan_jobs
            WHERE (status='QUEUED' OR (status='RUNNING' AND lease_until < :now)) AND attempt < :maxAttempts
            ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
            """).param("now", Timestamp.from(now)).param("maxAttempts", maxAttempts).query(UUID.class).optional();
        if (candidate.isEmpty()) return Optional.empty();
        jdbc.sql("""
            UPDATE scanjob.scan_jobs SET status='RUNNING', started_at=COALESCE(started_at,:now),
              lease_until=:lease, attempt=attempt+1, attempt_token=:token, version=version+1
            WHERE id=:id
            """).param("now", Timestamp.from(now)).param("lease", Timestamp.from(leaseUntil))
            .param("token", attemptToken).param("id", candidate.get()).update();
        return jdbc.sql("SELECT * FROM scanjob.scan_jobs WHERE id=:id").param("id", candidate.get())
            .query(JdbcScanJobStore::map).optional();
    }

    @Override public boolean heartbeat(UUID jobId, UUID attemptToken, Instant leaseUntil) {
        return jdbc.sql("UPDATE scanjob.scan_jobs SET lease_until=:lease WHERE id=:id AND attempt_token=:token AND status='RUNNING'")
            .param("lease", Timestamp.from(leaseUntil)).param("id", jobId).param("token", attemptToken).update() == 1;
    }

    @Override public boolean complete(UUID jobId, UUID token, int exitCode, byte[] report, String sha256,
                                      boolean partial, String scannerVersion, String schemaVersion, Instant now) {
        if (exitCode != 0 && exitCode != 2) return false;
        return jdbc.sql("""
            UPDATE scanjob.scan_jobs SET
              status=CASE WHEN status='CANCEL_REQUESTED' THEN 'CANCELLED' ELSE 'SUCCEEDED' END,
              outcome=CASE WHEN status='CANCEL_REQUESTED' THEN NULL WHEN :exitCode=0 THEN 'PASS' ELSE 'VIOLATION' END,
              report_bytes=CASE WHEN status='CANCEL_REQUESTED' THEN NULL ELSE :report END,
              report_sha256=CASE WHEN status='CANCEL_REQUESTED' THEN NULL ELSE :sha END,
              report_partial=CASE WHEN status='CANCEL_REQUESTED' THEN FALSE ELSE :partial END,
              scanner_version=:scanner, result_schema_version=:schema, completed_at=:now,
              lease_until=NULL, attempt_token=NULL, version=version+1
            WHERE id=:id AND attempt_token=:token AND status IN ('RUNNING','CANCEL_REQUESTED')
            """).param("exitCode", exitCode).param("report", report).param("sha", sha256).param("partial", partial)
            .param("scanner", scannerVersion).param("schema", schemaVersion).param("now", Timestamp.from(now))
            .param("id", jobId).param("token", token).update() == 1;
    }

    @Override public boolean fail(UUID jobId, UUID token, String code, String message, byte[] report,
                                  String sha256, Instant now) {
        return jdbc.sql("""
            UPDATE scanjob.scan_jobs SET
              status=CASE WHEN status='CANCEL_REQUESTED' THEN 'CANCELLED' ELSE 'FAILED' END,
              failure_code=CASE WHEN status='CANCEL_REQUESTED' THEN NULL ELSE :code END,
              failure_message=CASE WHEN status='CANCEL_REQUESTED' THEN NULL ELSE :message END,
              report_bytes=CASE WHEN status='CANCEL_REQUESTED' THEN NULL ELSE :report END,
              report_sha256=CASE WHEN status='CANCEL_REQUESTED' THEN NULL ELSE :sha END,
              report_partial=CASE WHEN status='CANCEL_REQUESTED' THEN FALSE ELSE :partial END,
              completed_at=:now, lease_until=NULL, attempt_token=NULL, version=version+1
            WHERE id=:id AND attempt_token=:token AND status IN ('RUNNING','CANCEL_REQUESTED')
            """).param("code", code).param("message", message).param("report", report).param("sha", sha256)
            .param("partial", report != null).param("now", Timestamp.from(now)).param("id", jobId).param("token", token)
            .update() == 1;
    }

    @Override public boolean existsForProject(UUID projectId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM scanjob.scan_jobs WHERE project_id=:projectId)")
            .param("projectId",projectId).query(Boolean.class).single();
    }

    private static ScanJobRecord map(ResultSet r, int row) throws SQLException {
        String outcome = r.getString("outcome");
        return new ScanJobRecord(r.getObject("id", UUID.class), r.getObject("project_id", UUID.class),
            r.getObject("repository_id", UUID.class), r.getObject("rule_set_version_id", UUID.class),
            r.getString("idempotency_key"), r.getString("request_sha256"), ScanJobStatus.valueOf(r.getString("status")),
            outcome == null ? null : ScanJobOutcome.valueOf(outcome), r.getObject("created_by", UUID.class),
            instant(r, "created_at"), instant(r, "started_at"), instant(r, "completed_at"), instant(r, "lease_until"),
            r.getInt("attempt"), r.getObject("attempt_token", UUID.class), r.getLong("version"),
            r.getString("failure_code"), r.getString("failure_message"), r.getBytes("report_bytes"),
            r.getString("report_sha256"), r.getBoolean("report_partial"), r.getString("scanner_version"),
            r.getString("result_schema_version"));
    }
    private static Instant instant(ResultSet r, String column) throws SQLException {
        Timestamp value = r.getTimestamp(column); return value == null ? null : value.toInstant();
    }
}
