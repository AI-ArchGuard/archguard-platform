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

/**
 * ScanJob 的 PostgreSQL 适配器，只访问本模块表，以数据库记录作为任务事实。
 * 认领使用短事务和行锁，完成/失败用 token 与状态条件更新；不负责文件或 Finding 的整体原子性。
 */
@Repository
public class JdbcScanJobStore implements ScanJobStore {
    private final JdbcClient jdbc;
    public JdbcScanJobStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 创建记录时初始化 attempt/version 和部分报告标记；项目内幂等键唯一性由表约束保护。 */
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

    /** 项目 ID 是幂等命名空间的一部分，同一键在不同项目互不冲突。 */
    @Override public Optional<ScanJobRecord> findByIdempotency(UUID projectId, String key) {
        return jdbc.sql("SELECT * FROM scanjob.scan_jobs WHERE project_id=:project AND idempotency_key=:key")
            .param("project", projectId).param("key", key).query(JdbcScanJobStore::map).optional();
    }
    /** 限定项目归属；用户授权由上层应用服务完成。 */
    @Override public Optional<ScanJobRecord> find(UUID projectId, UUID jobId) {
        return jdbc.sql("SELECT * FROM scanjob.scan_jobs WHERE project_id=:project AND id=:id")
            .param("project", projectId).param("id", jobId).query(JdbcScanJobStore::map).optional();
    }
    /** 后台按响应关联全局任务 ID，不能直接当作用户读取入口。 */
    @Override public Optional<ScanJobRecord> findById(UUID jobId) {
        return jdbc.sql("SELECT * FROM scanjob.scan_jobs WHERE id=:id").param("id", jobId)
            .query(JdbcScanJobStore::map).optional();
    }
    /** 用创建时间及 ID 双重排序稳定同时间记录的次序，分页参数已由应用层校验。 */
    @Override public List<ScanJobRecord> list(UUID projectId, int page, int size) {
        return jdbc.sql("SELECT * FROM scanjob.scan_jobs WHERE project_id=:project ORDER BY created_at DESC,id DESC LIMIT :size OFFSET :offset")
            .param("project", projectId).param("size", size).param("offset", page * size)
            .query(JdbcScanJobStore::map).list();
    }

    /**
     * 条件更新避免覆盖已到终态的任务；排队时立即取消，运行时只保存取消请求。
     * 更新后重读当前记录；此事务不发送 Runner 信号，也不能证明进程已经退出。
     */
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

    /**
     * 在一个事务中锁定并认领最早候选；SKIP LOCKED 让并发调度者跳过其他事务已锁住的任务。
     * 只选次数未耗尽的 QUEUED 或 lease_until 严格早于 now 的 RUNNING；到期才可被后续认领替换 token。
     * CANCEL_REQUESTED 不在候选中；达到 maxAttempts 的记录也不会在此被自动失败化。
     */
    @Override
    @Transactional
    public Optional<ScanJobRecord> claim(Instant now, Instant leaseUntil, UUID attemptToken, int maxAttempts) {
        Optional<UUID> candidate = jdbc.sql("""
            SELECT id FROM scanjob.scan_jobs
            WHERE (status='QUEUED' OR (status='RUNNING' AND lease_until < :now)) AND attempt < :maxAttempts
            ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
            """).param("now", Timestamp.from(now)).param("maxAttempts", maxAttempts).query(UUID.class).optional();
        if (candidate.isEmpty()) return Optional.empty();
        // 保留首次开始时间，每次认领增加次数并更换 token，使此前 attempt 的响应失去更新资格。
        jdbc.sql("""
            UPDATE scanjob.scan_jobs SET status='RUNNING', started_at=COALESCE(started_at,:now),
              lease_until=:lease, attempt=attempt+1, attempt_token=:token, version=version+1
            WHERE id=:id
            """).param("now", Timestamp.from(now)).param("lease", Timestamp.from(leaseUntil))
            .param("token", attemptToken).param("id", candidate.get()).update();
        return jdbc.sql("SELECT * FROM scanjob.scan_jobs WHERE id=:id").param("id", candidate.get())
            .query(JdbcScanJobStore::map).optional();
    }

    /** 仅当前 RUNNING token 可以续租；当前协调器未调用它，本更新也不增加任务版本。 */
    @Override public boolean heartbeat(UUID jobId, UUID attemptToken, Instant leaseUntil) {
        return jdbc.sql("UPDATE scanjob.scan_jobs SET lease_until=:lease WHERE id=:id AND attempt_token=:token AND status='RUNNING'")
            .param("lease", Timestamp.from(leaseUntil)).param("id", jobId).param("token", attemptToken).update() == 1;
    }

    /**
     * 0/2 均视为执行成功，分别保存 PASS/VIOLATION；取消请求优先转为 CANCELLED 并舍弃报告。
     * 单条 SQL 以当前 token 和状态排除迟到尝试，不检查租约是否到期；返回是否实际更新一行。
     * 此处不会撤销此前经 Finding 接口完成的写入。
     */
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

    /**
     * 当前 attempt 失败时可保存故障附件，有报告便标记 partial；本方法不校验附件契约。
     * 已请求取消则优先落为 CANCELLED，不保留失败信息和附件；匹配不到 token/状态时不更新。
     */
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

    /**
     * 导入专用完成：只接受 QUEUED 且幂等键等于 ci:jobId 的记录，结论由导入服务提供。
     * 无 Runner token/租约流程；整体导入事务位于 ScanJobImportService 的应用方法上。
     */
    @Override public boolean completeImported(UUID jobId, byte[] report, String sha256,
            String scannerVersion, String schemaVersion, ScanJobOutcome outcome, Instant now) {
        return jdbc.sql("""
            UPDATE scanjob.scan_jobs SET status='SUCCEEDED',outcome=:outcome,report_bytes=:report,
              report_sha256=:sha,scanner_version=:scanner,result_schema_version=:schema,
              completed_at=:now,version=version+1
            WHERE id=:id AND status='QUEUED' AND idempotency_key=:key
            """).param("outcome", outcome.name()).param("report", report).param("sha", sha256)
            .param("scanner", scannerVersion).param("schema", schemaVersion)
            .param("now", Timestamp.from(now)).param("id", jobId).param("key", "ci:" + jobId).update() == 1;
    }

    /** 为 Project 删除保护检查所有状态的记录，不把任务完成等同于项目内容已清空。 */
    @Override public boolean existsForProject(UUID projectId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM scanjob.scan_jobs WHERE project_id=:projectId)")
            .param("projectId",projectId).query(Boolean.class).single();
    }

    /** 还原内部快照，保留未开始/未结束时间及尚无规则结论的 null 语义。 */
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
