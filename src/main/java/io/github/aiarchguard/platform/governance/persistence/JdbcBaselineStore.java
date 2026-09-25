package io.github.aiarchguard.platform.governance.persistence;

import io.github.aiarchguard.platform.governance.BaselineVersionView;
import io.github.aiarchguard.platform.governance.Classification;
import io.github.aiarchguard.platform.governance.ClassifiedFinding;
import io.github.aiarchguard.platform.governance.ComparisonView;
import io.github.aiarchguard.platform.governance.internal.BaselineScope;
import io.github.aiarchguard.platform.governance.internal.BaselineStore;
import io.github.aiarchguard.platform.governance.internal.FindingSnapshot;
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
public class JdbcBaselineStore implements BaselineStore {
    private final JdbcClient jdbc;
    public JdbcBaselineStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public BaselineScope lockOrCreate(UUID projectId, UUID repositoryId, String branch, UUID rules) {
        jdbc.sql("""
            INSERT INTO governance.baseline_scopes(id,project_id,repository_id,target_branch,rule_set_version_id)
            VALUES (:id,:project,:repository,:branch,:rules) ON CONFLICT DO NOTHING
            """).param("id", UUID.randomUUID()).param("project", projectId).param("repository", repositoryId)
            .param("branch", branch).param("rules", rules).update();
        return jdbc.sql("""
            SELECT * FROM governance.baseline_scopes
            WHERE project_id=:project AND repository_id=:repository AND target_branch=:branch
              AND rule_set_version_id=:rules FOR UPDATE
            """).param("project", projectId).param("repository", repositoryId).param("branch", branch)
            .param("rules", rules).query(JdbcBaselineStore::mapScope).single();
    }

    @Override public Optional<BaselineScope> scope(UUID projectId, UUID repositoryId, String branch, UUID rules) {
        return jdbc.sql("""
            SELECT * FROM governance.baseline_scopes
            WHERE project_id=:project AND repository_id=:repository AND target_branch=:branch
              AND rule_set_version_id=:rules
            """).param("project", projectId).param("repository", repositoryId).param("branch", branch)
            .param("rules", rules).query(JdbcBaselineStore::mapScope).optional();
    }

    @Override public Optional<BaselineVersionView> byJob(UUID scopeId, UUID jobId) {
        return jdbc.sql(versionQuery() + " WHERE v.scope_id=:scope AND v.scan_job_id=:job")
            .param("scope", scopeId).param("job", jobId).query(JdbcBaselineStore::mapVersion).optional();
    }
    @Override public Optional<BaselineVersionView> version(UUID scopeId, UUID versionId) {
        return jdbc.sql(versionQuery() + " WHERE v.scope_id=:scope AND v.id=:version")
            .param("scope", scopeId).param("version", versionId).query(JdbcBaselineStore::mapVersion).optional();
    }
    @Override public List<BaselineVersionView> versions(UUID scopeId) {
        return jdbc.sql(versionQuery() + " WHERE v.scope_id=:scope ORDER BY v.version_number DESC")
            .param("scope", scopeId).query(JdbcBaselineStore::mapVersion).list();
    }

    @Override public BaselineVersionView insertVersion(BaselineScope scope, UUID jobId, String commitSha,
            String reportSha, String algorithm, UUID actorId, Instant now, List<FindingSnapshot> findings) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO governance.baseline_versions
              (id,scope_id,version_number,scan_job_id,commit_sha,report_sha256,fingerprint_version,created_by,created_at)
            VALUES (:id,:scope,:number,:job,:commit,:report,:algorithm,:actor,:at)
            """).param("id", id).param("scope", scope.id()).param("number", scope.nextVersion())
            .param("job", jobId).param("commit", commitSha).param("report", reportSha)
            .param("algorithm", algorithm).param("actor", actorId).param("at", Timestamp.from(now)).update();
        for (FindingSnapshot finding : findings) {
            jdbc.sql("""
                INSERT INTO governance.baseline_findings
                  (baseline_version_id,fingerprint,payload_sha256,rule_id,rule_version,severity,scanner_finding_id)
                VALUES (:baseline,:fingerprint,:payload,:rule,:version,:severity,:scanner)
                """).param("baseline", id).param("fingerprint", finding.fingerprint())
                .param("payload", finding.payloadSha256()).param("rule", finding.ruleId())
                .param("version", finding.ruleVersion()).param("severity", finding.severity())
                .param("scanner", finding.scannerFindingId()).update();
        }
        jdbc.sql("UPDATE governance.baseline_versions SET sealed=TRUE WHERE id=:id")
            .param("id", id).update();
        jdbc.sql("UPDATE governance.baseline_scopes SET next_version=next_version+1 WHERE id=:id")
            .param("id", scope.id()).update();
        return version(scope.id(), id).orElseThrow();
    }

    @Override public BaselineVersionView select(BaselineScope scope, UUID versionId, UUID actorId, Instant now) {
        long number = scope.selectionVersion() + 1;
        jdbc.sql("""
            UPDATE governance.baseline_scopes SET active_version_id=:version, selection_version=:selection
            WHERE id=:scope
            """).param("version", versionId).param("selection", number).param("scope", scope.id()).update();
        jdbc.sql("""
            INSERT INTO governance.baseline_selections
              (id,scope_id,baseline_version_id,selection_version,selected_by,selected_at)
            VALUES (:id,:scope,:version,:selection,:actor,:at)
            """).param("id", UUID.randomUUID()).param("scope", scope.id()).param("version", versionId)
            .param("selection", number).param("actor", actorId).param("at", Timestamp.from(now)).update();
        return version(scope.id(), versionId).orElseThrow();
    }

    @Override public List<FindingSnapshot> findings(UUID baselineVersionId) {
        return jdbc.sql("""
            SELECT fingerprint,payload_sha256,rule_id,rule_version,severity,scanner_finding_id
            FROM governance.baseline_findings WHERE baseline_version_id=:id ORDER BY fingerprint
            """).param("id", baselineVersionId).query((r, row) -> new FindingSnapshot(r.getString("fingerprint"),
                r.getString("payload_sha256"), r.getString("rule_id"), r.getString("rule_version"),
                r.getString("severity"), r.getString("scanner_finding_id"))).list();
    }

    @Override public Optional<ComparisonView> comparison(UUID baselineVersionId, UUID candidateJobId) {
        return jdbc.sql("""
            SELECT * FROM governance.comparisons
            WHERE baseline_version_id=:baseline AND candidate_job_id=:candidate
            """).param("baseline", baselineVersionId).param("candidate", candidateJobId)
            .query((r, row) -> mapComparison(r)).optional();
    }

    @Override public ComparisonView insertComparison(UUID baselineVersionId, UUID candidateJobId, String reportSha,
            String algorithm, Instant now, List<ClassifiedFinding> findings) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO governance.comparisons
              (id,baseline_version_id,candidate_job_id,candidate_report_sha256,fingerprint_version,created_at)
            VALUES (:id,:baseline,:candidate,:report,:algorithm,:at)
            """).param("id", id).param("baseline", baselineVersionId).param("candidate", candidateJobId)
            .param("report", reportSha).param("algorithm", algorithm).param("at", Timestamp.from(now)).update();
        for (ClassifiedFinding finding : findings) {
            jdbc.sql("""
                INSERT INTO governance.comparison_findings
                  (comparison_id,classification,fingerprint,payload_sha256,rule_id,rule_version,severity,scanner_finding_id)
                VALUES (:comparison,:classification,:fingerprint,:payload,:rule,:version,:severity,:scanner)
                """).param("comparison", id).param("classification", finding.classification().name())
                .param("fingerprint", finding.fingerprint()).param("payload", finding.payloadSha256())
                .param("rule", finding.ruleId()).param("version", finding.ruleVersion())
                .param("severity", finding.severity()).param("scanner", finding.scannerFindingId()).update();
        }
        jdbc.sql("UPDATE governance.comparisons SET sealed=TRUE WHERE id=:id")
            .param("id", id).update();
        return comparison(baselineVersionId, candidateJobId).orElseThrow();
    }

    private ComparisonView mapComparison(ResultSet r) throws SQLException {
        UUID id = r.getObject("id", UUID.class);
        List<ClassifiedFinding> findings = jdbc.sql("""
            SELECT * FROM governance.comparison_findings WHERE comparison_id=:id
            ORDER BY classification,fingerprint
            """).param("id", id).query((f, row) -> new ClassifiedFinding(
                Classification.valueOf(f.getString("classification")), f.getString("fingerprint"),
                f.getString("payload_sha256"), f.getString("rule_id"), f.getString("rule_version"),
                f.getString("severity"), f.getString("scanner_finding_id"))).list();
        return new ComparisonView(id, r.getObject("baseline_version_id", UUID.class),
            r.getObject("candidate_job_id", UUID.class), r.getString("candidate_report_sha256"),
            r.getString("fingerprint_version"), r.getTimestamp("created_at").toInstant(), findings);
    }
    private static String versionQuery() {
        return """
            SELECT v.*,s.project_id,s.repository_id,s.target_branch,s.rule_set_version_id,
              s.active_version_id,s.selection_version
            FROM governance.baseline_versions v JOIN governance.baseline_scopes s ON s.id=v.scope_id
            """;
    }
    private static BaselineScope mapScope(ResultSet r, int row) throws SQLException {
        return new BaselineScope(r.getObject("id", UUID.class), r.getObject("project_id", UUID.class),
            r.getObject("repository_id", UUID.class), r.getString("target_branch"),
            r.getObject("rule_set_version_id", UUID.class), r.getObject("active_version_id", UUID.class),
            r.getLong("next_version"), r.getLong("selection_version"));
    }
    private static BaselineVersionView mapVersion(ResultSet r, int row) throws SQLException {
        UUID id = r.getObject("id", UUID.class);
        return new BaselineVersionView(id, r.getObject("project_id", UUID.class),
            r.getObject("repository_id", UUID.class), r.getString("target_branch"),
            r.getObject("rule_set_version_id", UUID.class), r.getLong("version_number"),
            r.getObject("scan_job_id", UUID.class), r.getString("commit_sha"), r.getString("report_sha256"),
            r.getString("fingerprint_version"), r.getObject("created_by", UUID.class),
            r.getTimestamp("created_at").toInstant(), id.equals(r.getObject("active_version_id", UUID.class)),
            r.getLong("selection_version"));
    }
}
