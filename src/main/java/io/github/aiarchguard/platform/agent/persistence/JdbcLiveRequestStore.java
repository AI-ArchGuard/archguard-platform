package io.github.aiarchguard.platform.agent.persistence;

import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.agent.AgentConflictException;
import io.github.aiarchguard.platform.agent.internal.LiveAccountingOperations;
import io.github.aiarchguard.platform.agent.internal.LiveRequestStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

/**
 * 真实请求关联的 JDBC 实现；
 * 关联插入由数据库触发器保护模板唯一槽位，领取时精确匹配请求者、版本投影及完整不可变关联，不预留模型费用。
 */
@Repository
class JdbcLiveRequestStore implements LiveRequestStore {
    private final JdbcClient jdbc;

    JdbcLiveRequestStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 关联插入会触发数据库模板唯一槽位约束；只把指定槽位冲突转成幂等冲突，其他数据库错误保持失败并由外层事务回滚。 */
    @Override public void insert(Link link) {
        try {
            jdbc.sql("""
            INSERT INTO agent.live_request_links(request_id,project_id,batch_id,template_id,enablement_id,
                credential_version,settings_revision,created_at)
            VALUES (:request,:project,:batch,:template,:enablement,:credential,:revision,:at)
            """).param("request", link.requestId()).param("project", link.projectId())
            .param("batch", link.batchId()).param("template", link.templateId()).param("enablement", link.enablementId())
            .param("credential", link.credentialVersion()).param("revision", link.settingsRevision())
            .param("at", Timestamp.from(link.createdAt())).update();
        } catch (DataAccessException failure) {
            if (LiveTemplateSlotConflict.isConflict(failure)) throw new AgentConflictException();
            throw failure;
        }
    }

    @Override public Optional<Link> find(UUID projectId, UUID requestId) {
        return jdbc.sql("SELECT * FROM agent.live_request_links WHERE project_id=:project AND request_id=:request")
            .param("project", projectId).param("request", requestId).query((rs, row) -> new Link(
                rs.getObject("request_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("batch_id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getObject("enablement_id", UUID.class), rs.getObject("credential_version", UUID.class),
                rs.getLong("settings_revision"), rs.getTimestamp("created_at").toInstant())).optional();
    }

    /**
     * 一次 UPDATE 精确匹配请求者、用途/版本和全部不可变关联，CAS 只放行 QUEUED 胜者；费用预留仍在后续独立事务。
     */
    @Override public boolean claim(Link link, UUID requester, Instant at) {
        if (link == null || requester == null || at == null || link.requestId() == null || link.projectId() == null
                || link.batchId() == null || link.templateId() == null || link.enablementId() == null
                || link.credentialVersion() == null || link.createdAt() == null || link.settingsRevision() < 0) return false;
        return jdbc.sql("""
            UPDATE agent.requests r SET state='RUNNING', updated_at=:at
            WHERE r.id=:request AND r.project_id=:project AND r.requester_id=:requester AND r.state='QUEUED'
                AND ((r.bindings->>'modelProfileVersion'=:profile AND r.bindings->>'priceCatalogVersion'=:catalog)
                    OR (r.bindings->>'modelProfileVersion'=:context_profile AND r.bindings->>'priceCatalogVersion'=:context_catalog))
                AND r.bindings->>'modelProtocolVersion'='responses-v1-restricted'
                AND r.bindings->>'modelId'='deepseek-flash'
                AND r.bindings->>'outputSchemaVersion'='0.1.0'
                AND r.model_input->>'schemaVersion'='0.1.0'
                AND r.model_input->>'purpose'=r.purpose
                AND ((r.purpose='FINDING_EXPLANATION'
                        AND r.bindings->>'promptVersion'=:explanation
                        AND r.model_input->>'promptVersion'=:explanation)
                    OR (r.purpose='PR_SUMMARY'
                        AND r.bindings->>'promptVersion'=:summary
                        AND r.model_input->>'promptVersion'=:summary))
                AND EXISTS (SELECT 1 FROM agent.live_request_links l
                    JOIN agent.live_batches b ON b.project_id=l.project_id AND b.id=l.batch_id
                    WHERE l.request_id=r.id AND l.project_id=r.project_id
                        AND b.cost_policy_version=r.bindings->>'priceCatalogVersion'
                        AND l.batch_id=:batch AND l.template_id=:template AND l.enablement_id=:enablement
                        AND l.credential_version=:credential AND l.settings_revision=:revision AND l.created_at=:created)
            """).param("request", link.requestId()).param("project", link.projectId()).param("requester", requester)
            .param("batch", link.batchId()).param("template", link.templateId()).param("enablement", link.enablementId())
            .param("credential", link.credentialVersion()).param("revision", link.settingsRevision())
            .param("created", Timestamp.from(link.createdAt())).param("at", Timestamp.from(at))
            .param("profile", LiveAccountingOperations.MODEL_PROFILE).param("catalog", LiveCostPolicy.VERSION)
            .param("context_profile", LiveCostPolicy.CONTEXT_MODEL_PROFILE).param("context_catalog", LiveCostPolicy.CONTEXT_VERSION)
            .param("explanation", LiveAccountingOperations.EXPLANATION_PROMPT).param("summary", LiveAccountingOperations.SUMMARY_PROMPT)
            .update() == 1;
    }
}
