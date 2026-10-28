package io.github.aiarchguard.platform.agent.persistence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentConflictException;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.LiveBatchExecutionView;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.agent.internal.LiveBatchExecutionStore;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 以单条 SQL 的同一 MVCC 快照读取批次执行事实，再严格校验槽位、状态与费用总数；
 * 私有查询客户端限时限行，不授予发送权限或修补历史。
 */
@Repository
class JdbcLiveBatchExecutionStore implements LiveBatchExecutionStore {
    private static final Set<String> FAILURE_CODES = Set.of("MODEL_DISABLED", "MODEL_UNAVAILABLE", "MODEL_TIMEOUT",
        "QUOTA_EXHAUSTED", "OUTPUT_INVALID", "CITATION_INVALID", "AUTHORIZATION_REVOKED", "INTERNAL_ERROR");
    private static final Set<String> STOP_CODES = Set.of("MODEL_DISABLED", "MODEL_UNAVAILABLE", "MODEL_TIMEOUT",
        "QUOTA_EXHAUSTED", "OUTPUT_INVALID", "CITATION_INVALID", "AUTHORIZATION_REVOKED", "INTERNAL_ERROR", "INPUT_TOKEN_LIMIT");
    private static final Set<String> REQUEST_STATES = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED");
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    JdbcLiveBatchExecutionStore(DataSource source, ObjectMapper mapper) {
        // A private template: never change timeout/maxRows on a shared application client.
        var bounded = new JdbcTemplate(source);
        bounded.setQueryTimeout(5);
        bounded.setMaxRows(1);
        this.jdbc = JdbcClient.create(bounded);
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    /** 单语句保证模板、槽位、请求和账本来自同一数据库快照；旧策略显式冲突，损坏或不一致数据返回不可用而非部分成功。 */
    @Override public Optional<LiveBatchExecutionView> load(UUID projectId, UUID batchId) {
        if (projectId == null || batchId == null) throw new AgentUnavailableException();
        try {
            return jdbc.sql(SNAPSHOT).param("project", projectId).param("batch", batchId)
                .param("policy", LiveCostPolicy.CONTEXT_VERSION).param("profile", LiveCostPolicy.CONTEXT_MODEL_PROFILE)
                .query((row, index) -> {
                    if (!LiveCostPolicy.CONTEXT_VERSION.equals(row.getString("cost_policy_version"))) {
                        throw new AgentConflictException();
                    }
                    if (!row.getBoolean("valid_snapshot")) throw new AgentUnavailableException();
                    String json = row.getString("snapshot");
                    if (json == null || json.length() > 16384) throw new AgentUnavailableException();
                    try {
                        var value = mapper.readValue(json, LiveBatchExecutionView.class);
                        validate(value, projectId, batchId);
                        return value;
                    } catch (Exception invalid) { throw new AgentUnavailableException(); }
                }).optional();
        } catch (AgentConflictException legacy) { throw legacy; }
        catch (RuntimeException unavailable) { throw new AgentUnavailableException(); }
    }

    /**
     * 重新核对公开投影的顺序、唯一性、状态关系及费用守恒；
     * 逐次重算 SETTLED 费用并与批次合计比较，不依赖 SQL 拼装就直接信任。
     */
    private static void validate(LiveBatchExecutionView value, UUID project, UUID batchId) {
        require(value != null && "0.1.0".equals(value.schemaVersion()) && value.observedAt() != null
            && LiveCostPolicy.CONTEXT_VERSION.equals(value.costPolicyVersion()) && value.batch() != null
            && value.accounting() != null && value.templates() != null && value.templates().size() == 2);
        var batch = value.batch(); var accounting = value.accounting();
        require(batchId.equals(batch.id()) && project.equals(batch.projectId()) && batch.enablementId() != null
            && batch.approvedBy() != null && batch.approvedAt() != null && batch.expiresAt() != null
            && batch.expiresAt().isAfter(batch.approvedAt()) && batch.manifestSha256() != null
            && batch.manifestSha256().matches("[a-f0-9]{64}") && batch.maxRequests() == 2
            && batch.maxCostMicrousd() >= 2 * LiveCostPolicy.CONTEXT_RESERVATION
            && batch.maxCostMicrousd() <= LiveCostPolicy.BATCH_LIMIT);
        require(accounting.attemptCount() >= 0 && accounting.attemptCount() <= 2
            && accounting.reservedMicrousd() >= 0 && accounting.spentMicrousd() >= 0
            && accounting.reservedMicrousd() <= batch.maxCostMicrousd()
            && accounting.spentMicrousd() <= batch.maxCostMicrousd() - accounting.reservedMicrousd());
        Set<UUID> templates = new HashSet<>(), requests = new HashSet<>(), attempts = new HashSet<>();
        long reserved = 0, spent = 0; int count = 0;
        for (int index = 0; index < 2; index++) {
            var item = value.templates().get(index);
            require(item != null && item.templateRequestId() != null && templates.add(item.templateRequestId())
                && (index == 0 ? "FINDING_EXPLANATION" : "PR_SUMMARY").equals(item.purpose()));
            var request = item.request(); var attempt = item.attempt();
            if (request == null) { require(attempt == null); continue; }
            require(request.id() != null && requests.add(request.id()) && !request.id().equals(item.templateRequestId())
                && request.state() != null && REQUEST_STATES.contains(request.state()) && request.traceId() != null
                && request.traceId().matches("[0-9a-f]{32}") && request.createdAt() != null && request.updatedAt() != null
                && !request.updatedAt().isBefore(request.createdAt()));
            require("FAILED".equals(request.state())
                ? request.failureCode() != null && FAILURE_CODES.contains(request.failureCode()) : request.failureCode() == null);
            if (attempt == null) { require(!"SUCCEEDED".equals(request.state())); continue; }
            count++;
            require(attempt.attemptId() != null && attempts.add(attempt.attemptId()) && attempt.utcDay() != null
                && attempt.createdAt() != null && LiveCostPolicy.CONTEXT_VERSION.equals(attempt.priceCatalogVersion())
                && attempt.reservedMicrousd() == LiveCostPolicy.CONTEXT_RESERVATION
                && !"QUEUED".equals(request.state()));
            var outcome = attempt.outcome();
            if (outcome == null) {
                require(!"SUCCEEDED".equals(request.state())); reserved += attempt.reservedMicrousd(); continue;
            }
            require(outcome.recordedAt() != null);
            if ("UNKNOWN".equals(outcome.state())) {
                require(outcome.actualMicrousd() == null && outcome.usage() == null && !"SUCCEEDED".equals(request.state()));
                reserved += attempt.reservedMicrousd();
            } else {
                require("SETTLED".equals(outcome.state()) && outcome.actualMicrousd() != null && outcome.usage() != null);
                long cost = LiveCostPolicy.cost(LiveCostPolicy.CONTEXT_VERSION, outcome.usage());
                require(cost == outcome.actualMicrousd() && cost <= attempt.reservedMicrousd());
                if ("SUCCEEDED".equals(request.state())) {
                    require(outcome.usage().inputTokens() <= LiveCostPolicy.POSTCALL_INPUT_THRESHOLD);
                }
                spent += cost;
            }
        }
        require(accounting.attemptCount() == count && accounting.reservedMicrousd() == reserved && accounting.spentMicrousd() == spent);
        if (value.stop() != null) {
            var stop = value.stop();
            require(stop.requestId() != null && requests.contains(stop.requestId()) && stop.code() != null
                && STOP_CODES.contains(stop.code()) && stop.stoppedAt() != null);
        }
    }

    private static void require(boolean valid) { if (!valid) throw new AgentUnavailableException(); }

    // Each bounded child projection has at most three rows: a third is corruption, never a truncated
    // valid response. All joins/aggregates share this one PostgreSQL statement's MVCC snapshot.
    private static final String SNAPSHOT = """
        SELECT b.cost_policy_version,
            (u.batch_id IS NOT NULL
                AND b.manifest->>'schemaVersion'='0.1.0' AND b.manifest->>'projectId'=b.project_id::text
                AND jsonb_typeof(b.manifest->'templates')='array'
                AND jsonb_array_length(CASE WHEN jsonb_typeof(b.manifest->'templates')='array'
                    THEN b.manifest->'templates' ELSE '[]'::jsonb END)=2
                AND t.template_count=2 AND t.unique_count=2 AND t.valid_rows IS TRUE
                AND t.allocated_count=(SELECT count(*) FROM (
                    SELECT request_id FROM agent.live_template_request_slots WHERE project_id=b.project_id AND batch_id=b.id LIMIT 3
                ) slots)
                AND t.allocated_count=(SELECT count(*) FROM (
                    SELECT request_id FROM agent.live_request_links WHERE project_id=b.project_id AND batch_id=b.id LIMIT 3
                ) links)
                AND t.attempt_count=(SELECT count(*) FROM (
                    SELECT request_id FROM agent.live_attempts WHERE project_id=b.project_id AND batch_id=b.id LIMIT 3
                ) attempts)) AS valid_snapshot,
            jsonb_build_object(
                'schemaVersion','0.1.0','observedAt',statement_timestamp(),
                'batch',jsonb_build_object('id',b.id,'projectId',b.project_id,'enablementId',b.enablement_id,
                    'approvedBy',b.approved_by,'approvedAt',b.approved_at,'manifestSha256',b.manifest_sha256,
                    'expiresAt',b.expires_at,'maxRequests',b.max_requests,'maxCostMicrousd',b.max_cost_microusd,
                    'revoked',rev.batch_id IS NOT NULL),
                'costPolicyVersion',b.cost_policy_version,
                'accounting',jsonb_build_object('attemptCount',u.attempts,'reservedMicrousd',u.reserved_microusd,'spentMicrousd',u.spent_microusd),
                'stop',CASE WHEN stop.batch_id IS NULL THEN NULL ELSE
                    jsonb_build_object('requestId',stop.request_id,'code',stop.code,'stoppedAt',stop.created_at) END,
                'templates',t.items) AS snapshot
        FROM agent.live_batches b
        LEFT JOIN agent.live_batch_usage u ON u.batch_id=b.id
        LEFT JOIN agent.live_batch_revocations rev ON rev.batch_id=b.id
        LEFT JOIN agent.live_batch_stops stop ON stop.batch_id=b.id AND stop.project_id=b.project_id
        LEFT JOIN LATERAL (
            SELECT count(*) AS template_count,count(DISTINCT m.item->>'templateRequestId') AS unique_count,
                count(s.request_id) AS allocated_count,count(a.request_id) AS attempt_count,
                bool_and(COALESCE(
                    jsonb_typeof(m.item)='object' AND template.id IS NOT NULL
                    AND template.purpose=m.item->'selection'->>'purpose'
                    AND template.state IN ('SUCCEEDED','FAILED')
                    AND (s.request_id IS NULL OR (
                        l.request_id IS NOT NULL AND l.project_id=b.project_id AND l.batch_id=b.id
                        AND l.template_id=s.template_id AND l.enablement_id=b.enablement_id
                        AND l.created_at=s.created_at AND r.id IS NOT NULL AND r.project_id=b.project_id
                        AND r.purpose=m.item->'selection'->>'purpose'
                        AND r.bindings->>'priceCatalogVersion'=:policy AND r.bindings->>'modelProfileVersion'=:profile))
                    AND (a.request_id IS NULL OR (
                        s.request_id IS NOT NULL AND a.project_id=b.project_id AND a.batch_id=b.id
                        AND a.template_id=s.template_id AND a.deployment_id=b.deployment_id
                        AND a.enablement_id=l.enablement_id AND a.credential_version=l.credential_version
                        AND a.settings_revision=l.settings_revision))
                    AND (o.request_id IS NULL OR (
                        (o.state='UNKNOWN' AND o.usage IS NULL AND o.actual_microusd IS NULL)
                        OR (o.state='SETTLED' AND o.actual_microusd IS NOT NULL AND jsonb_typeof(o.usage)='object'
                            AND jsonb_exists_all(o.usage,ARRAY['inputTokens','outputTokens','cachedInputTokens','totalTokens','reasoningTokens'])
                            AND o.usage-ARRAY['inputTokens','outputTokens','cachedInputTokens','totalTokens','reasoningTokens']='{}'::jsonb
                            AND jsonb_typeof(o.usage->'inputTokens')='number'
                            AND jsonb_typeof(o.usage->'outputTokens')='number'
                            AND jsonb_typeof(o.usage->'cachedInputTokens')='number'
                            AND jsonb_typeof(o.usage->'totalTokens')='number'
                            AND jsonb_typeof(o.usage->'reasoningTokens')='number'))), false)) AS valid_rows,
                jsonb_agg(jsonb_build_object(
                    'templateRequestId',m.item->>'templateRequestId','purpose',m.item->'selection'->>'purpose',
                    'request',CASE WHEN s.request_id IS NULL THEN NULL ELSE jsonb_build_object(
                        'id',r.id,'state',r.state,'failureCode',r.failure->>'code','traceId',r.trace_id,
                        'createdAt',r.created_at,'updatedAt',r.updated_at) END,
                    'attempt',CASE WHEN a.request_id IS NULL THEN NULL ELSE jsonb_build_object(
                        'attemptId',a.attempt_id,'utcDay',a.utc_day,'priceCatalogVersion',a.price_version,
                        'reservedMicrousd',a.reserved_microusd,'createdAt',a.created_at,
                        'outcome',CASE WHEN o.request_id IS NULL THEN NULL ELSE jsonb_build_object(
                            'state',o.state,'actualMicrousd',o.actual_microusd,'recordedAt',o.created_at,
                            'usage',CASE WHEN o.state='SETTLED' THEN jsonb_build_object(
                                'inputTokens',(o.usage->>'inputTokens')::integer,'outputTokens',(o.usage->>'outputTokens')::integer,
                                'cachedInputTokens',(o.usage->>'cachedInputTokens')::integer,
                                'totalTokens',(o.usage->>'totalTokens')::integer,
                                'reasoningTokens',(o.usage->>'reasoningTokens')::integer) ELSE NULL END) END) END)
                    ORDER BY CASE WHEN m.item->'selection'->>'purpose'='FINDING_EXPLANATION' THEN 0 ELSE 1 END,
                        m.item->>'templateRequestId') AS items
            FROM (SELECT item FROM jsonb_array_elements(CASE WHEN jsonb_typeof(b.manifest->'templates')='array'
                THEN b.manifest->'templates' ELSE '[]'::jsonb END) AS element(item) LIMIT 3) m
            LEFT JOIN agent.requests template ON template.project_id=b.project_id AND template.id::text=m.item->>'templateRequestId'
            LEFT JOIN agent.live_template_request_slots s ON s.project_id=b.project_id AND s.batch_id=b.id
                AND s.template_id::text=m.item->>'templateRequestId'
            LEFT JOIN agent.live_request_links l ON l.request_id=s.request_id
            LEFT JOIN agent.requests r ON r.id=s.request_id
            LEFT JOIN agent.live_attempts a ON a.request_id=s.request_id
            LEFT JOIN agent.live_outcomes o ON o.request_id=a.request_id
        ) t ON b.cost_policy_version=:policy
        WHERE b.project_id=:project AND b.id=:batch
        """;
}
