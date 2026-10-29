package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.GovernanceConflictException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 门禁落库的事务边界，由求值应用服务传入已计算结果，存储端口写入结果及例外命中后封存。
 * 与基线比较事务分开，因而可在比较失败后保存明确 ERROR；不是覆盖整个治理流程的事务。
 */
@Service
class GateWriteService {
    private final GateEvaluationStore store;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;

    GateWriteService(GateEvaluationStore store, AuditRecorder audit, TraceIdProvider traceIds) {
        this.store = store; this.audit = audit; this.traceIds = traceIds;
    }

    /** 同事务保存结果、封存与成功写入审计；并发重复键只接受摘要相同的原结果。 */
    @Transactional
    public GateEvaluationView persist(GateEvaluationView value, String key, String requestSha256, UUID actorId) {
        if (!store.insert(value, key, requestSha256, actorId)) {
            // 应用层预查之后仍可能有并发竞争，以数据库唯一键的胜者为准。
            GateEvaluationStore.Stored existing = store.byKey(value.projectId(), key).orElseThrow();
            if (!existing.requestSha256().equals(requestSha256)) {
                throw new GovernanceConflictException("Idempotency-Key is bound to different gate inputs");
            }
            return existing.view();
        }
        store.seal(value.id());
        // SUCCESS 表示治理写入成功；门禁业务结论仍可能为 FAIL 或 ERROR。
        audit.record(new AuditEvent(actorId, value.projectId(), "governance.gate.evaluate", AuditResult.SUCCESS,
            traceIds.currentTraceId(), Map.of("gateEvaluationId", value.id(), "outcome", value.outcome().name(),
                "ciExitCode", value.ciExitCode(), "blockedCount", value.blockedCount())));
        return value;
    }
}
