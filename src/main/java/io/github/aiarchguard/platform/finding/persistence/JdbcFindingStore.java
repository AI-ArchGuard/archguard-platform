package io.github.aiarchguard.platform.finding.persistence;

import io.github.aiarchguard.platform.finding.DispositionView;
import io.github.aiarchguard.platform.finding.EvidenceView;
import io.github.aiarchguard.platform.finding.FindingDisposition;
import io.github.aiarchguard.platform.finding.FindingView;
import io.github.aiarchguard.platform.finding.internal.FindingStore;
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
 * Finding 查询及人工处置的 JDBC 适配器，仅访问本模块表，权限由上游应用服务先检查。
 * 条件更新和追加处置历史在本适配器事务中完成；外部审计由应用服务随后调用。
 */
@Repository
public class JdbcFindingStore implements FindingStore {
    private final JdbcClient jdbc;
    public JdbcFindingStore(JdbcClient jdbc) { this.jdbc=jdbc; }

    /** 按现有 severity、id 排序分页，不额外计算总数；合法分页范围由应用服务保证。 */
    @Override public List<FindingView> list(UUID projectId, UUID jobId, int page, int size) {
        return jdbc.sql("SELECT * FROM finding.findings WHERE project_id=:project AND job_id=:job ORDER BY severity,id LIMIT :size OFFSET :offset")
            .param("project",projectId).param("job",jobId).param("size",size).param("offset",page*size)
            .query((r,row)->mapFinding(r)).list();
    }
    /** 三个标识共同限定归属，防止仅凭 Finding UUID 读取另一个项目或任务的记录。 */
    @Override public Optional<FindingView> find(UUID projectId, UUID jobId, UUID findingId) {
        return jdbc.sql("SELECT * FROM finding.findings WHERE project_id=:project AND job_id=:job AND id=:id")
            .param("project",projectId).param("job",jobId).param("id",findingId).query((r,row)->mapFinding(r)).optional();
    }
    /** 证据与 Finding 一样按项目、任务和自身标识读取。 */
    @Override public Optional<EvidenceView> findEvidence(UUID projectId, UUID jobId, UUID evidenceId) {
        return jdbc.sql("SELECT * FROM finding.evidences WHERE project_id=:project AND job_id=:job AND id=:id")
            .param("project",projectId).param("job",jobId).param("id",evidenceId).query(JdbcFindingStore::mapEvidence).optional();
    }
    /**
     * expectedVersion 是乐观并发条件：更新命中才递增版本并插入历史，历史失败则回滚更新。
     * previous 仅写入历史，SQL 更新条件只比较 id 和版本；此事务不包含外部审计。
     */
    @Override
    @Transactional
    public boolean updateDisposition(UUID findingId, FindingDisposition previous, FindingDisposition next,
                                     long expectedVersion, String reason, UUID actorId, Instant now, UUID historyId) {
        int changed=jdbc.sql("UPDATE finding.findings SET disposition=:next,version=version+1 WHERE id=:id AND version=:version")
            .param("next",next.name()).param("id",findingId).param("version",expectedVersion).update();
        // 版本已变化或行不存在时不追加历史，由应用层把 false 转为版本冲突。
        if(changed==0) return false;
        jdbc.sql("""
            INSERT INTO finding.disposition_history
              (id,finding_id,previous_disposition,new_disposition,reason,actor_id,occurred_at,version)
            VALUES (:id,:finding,:previous,:next,:reason,:actor,:at,:version)
            """).param("id",historyId).param("finding",findingId).param("previous",previous.name())
            .param("next",next.name()).param("reason",reason).param("actor",actorId).param("at",Timestamp.from(now))
            .param("version",expectedVersion+1).update();
        return true;
    }
    /** 调用方已核实项目与任务归属；历史按时间及 ID 排序，保留各次处置的版本。 */
    @Override public List<DispositionView> history(UUID findingId) {
        return jdbc.sql("SELECT * FROM finding.disposition_history WHERE finding_id=:id ORDER BY occurred_at,id")
            .param("id",findingId).query(JdbcFindingStore::mapHistory).list();
    }
    /** 组装公开视图时另查证据关联；数据库空路径还原为无源码位置的 Finding。 */
    private FindingView mapFinding(ResultSet r) throws SQLException {
        UUID id=r.getObject("id",UUID.class);
        List<UUID> evidenceIds=jdbc.sql("SELECT evidence_id FROM finding.finding_evidences WHERE finding_id=:id ORDER BY evidence_id")
            .param("id",id).query(UUID.class).list();
        String path=r.getString("path");
        FindingView.SourceLocation location=path==null?null:new FindingView.SourceLocation(path,r.getInt("start_line"),
            r.getInt("start_column"),r.getInt("end_line"),r.getInt("end_column"));
        return new FindingView(id,r.getObject("project_id",UUID.class),r.getObject("job_id",UUID.class),
            r.getString("scanner_finding_id"),r.getString("fingerprint"),r.getString("rule_id"),
            r.getString("rule_version"),r.getString("severity"),r.getString("subject_id"),r.getString("message"),
            location,FindingDisposition.valueOf(r.getString("disposition")),r.getLong("version"),evidenceIds);
    }
    private static EvidenceView mapEvidence(ResultSet r,int row) throws SQLException {
        var location=new FindingView.SourceLocation(r.getString("path"),r.getInt("start_line"),r.getInt("start_column"),
            r.getInt("end_line"),r.getInt("end_column"));
        return new EvidenceView(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),
            r.getObject("job_id",UUID.class),r.getString("scanner_evidence_id"),r.getString("kind"),
            r.getString("summary"),location);
    }
    private static DispositionView mapHistory(ResultSet r,int row) throws SQLException {
        return new DispositionView(r.getObject("id",UUID.class),r.getObject("finding_id",UUID.class),
            FindingDisposition.valueOf(r.getString("previous_disposition")),
            FindingDisposition.valueOf(r.getString("new_disposition")),r.getString("reason"),
            r.getObject("actor_id",UUID.class),r.getTimestamp("occurred_at").toInstant(),r.getLong("version"));
    }
}
