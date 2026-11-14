package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.internal.DailyAgentStore.*;
import io.github.aiarchguard.platform.agent.internal.LiveAccountingOperations.*;
import io.github.aiarchguard.platform.audit.*;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 日常记账的内部应用服务；预留与发送复核沿用原执行期限。 */
@Service
public final class DailyAccountingService {
    private static final long RESERVATION = LiveCostPolicy.CONTEXT_RESERVATION;
    private final DailyAgentControlService controls;
    private final DailyAgentStore store;
    private final AgentStore requests;
    private final LiveDatabaseWork database;
    private final AuditRecorder audit;
    private final TraceIdProvider traces;
    private final Clock clock;
    private final long projectLimit;
    private final long deploymentLimit;

    public DailyAccountingService(DailyAgentControlService controls, DailyAgentStore store, AgentStore requests,
            LiveDatabaseWork database, AuditRecorder audit, TraceIdProvider traces, Clock clock,
            @Value("${archguard.agent.live-accounting.project-day-limit-microusd:5000000}") long projectLimit,
            @Value("${archguard.agent.live-accounting.deployment-day-limit-microusd:20000000}") long deploymentLimit) {
        this.controls = controls; this.store = store; this.requests = requests; this.database = database;
        this.audit = audit; this.traces = traces; this.clock = clock;
        this.projectLimit = projectLimit; this.deploymentLimit = deploymentLimit;
    }

    public Admission reserve(UUID project, UUID request, LongSupplier remaining) {
        return database.transaction(remaining, false, () -> {
            controls.authorizeRequestRead(project);
            var link = prefix(project, request);
            var snapshot = snapshot(link);
            var existing = store.attempt(project, request);
            if (existing.isPresent()) {
                var attempt = existing.orElseThrow();
                exactAttempt(link, attempt);
                return new Admission(attempt.attemptId(), attempt.reserved(), false);
            }
            var grant = controls.requireCurrent(project, link.grantId(), snapshot.view().purpose(), remaining);
            validateRunning(link, grant, snapshot);
            limits();
            LocalDate day = today();
            var money = store.money();
            var deployment = money.lock("DEPLOYMENT", grant.deploymentId(), day);
            var projectMoney = money.lock("PROJECT", project, day);
            store.lockRequest(project, request);
            // 等待任意账本锁后，重新检查当前权限、冻结内容、有效期和 UTC 日。
            grant = controls.requireCurrent(project, link.grantId(), snapshot.view().purpose(), remaining);
            snapshot = snapshot(link);
            validateRunning(link, grant, snapshot);
            if (!day.equals(today())) throw invalid();
            var usage = grant.usage();
            if (usage.attempts() >= grant.maxAttempts()
                    || !fits(usage.reservedMicrousd(), usage.spentMicrousd(), grant.maxCostMicrousd())
                    || !fits(deployment.reserved(), deployment.spent(), deploymentLimit)
                    || !fits(projectMoney.reserved(), projectMoney.spent(), projectLimit)) throw new AgentQuotaExceededException();
            var attempt = new Attempt(request, project, grant.id(), UUID.randomUUID(), grant.deploymentId(),
                link.credentialVersion(), link.settingsRevision(), link.runtimeBindingDigest(), day,
                LiveCostPolicy.CONTEXT_VERSION, RESERVATION, null, null);
            money.reserve("DEPLOYMENT", grant.deploymentId(), day, RESERVATION);
            money.reserve("PROJECT", project, day, RESERVATION);
            store.reserveUsage(project, grant.id(), request, RESERVATION);
            store.insertAttempt(attempt, clock.instant());
            record(snapshot, "agent.daily.reserved", Map.of("requestId", request, "attemptId", attempt.attemptId(),
                "estimatedMicrousd", RESERVATION, "priceVersion", attempt.priceVersion()));
            return new Admission(attempt.attemptId(), RESERVATION, true);
        });
    }

    public SendApproval checkBeforeSend(UUID project, UUID request, LongSupplier remaining) {
        return database.transaction(remaining, false, () -> {
            controls.authorizeRequestRead(project);
            var link = prefix(project, request);
            var attempt = attempt(link);
            lockMoney(attempt);
            store.lockRequest(project, request);
            var snapshot = snapshot(link);
            var grant = controls.requireReserved(project, request, snapshot.view().purpose(), remaining);
            validateRunning(link, grant, snapshot);
            if (attempt.state() != null || !today().equals(attempt.day())) throw invalid();
            limits();
            return new SendApproval(link.credentialVersion(), Set.of("deepseek-flash"));
        });
    }

    public Outcome settle(UUID project, UUID request, String price, LiveTokenUsage usage) {
        return database.cleanup(() -> finish(project, request, price, usage));
    }
    public Outcome unknown(UUID project, UUID request) {
        return database.cleanup(() -> finish(project, request, null, null));
    }
    /** 费用清理只依据可信持久化关联，当前授权失效不得拒记已经发生的费用。 */
    private Outcome finish(UUID project, UUID request, String price, LiveTokenUsage usage) {
        var link = prefix(project, request);
        var attempt = attempt(link);
        lockMoney(attempt);
        store.lockRequest(project, request);
        var snapshot = snapshot(link);
        if (attempt.state() != null) return new Outcome(attempt.state(), attempt.actual());
        Long actual = null;
        if (attempt.priceVersion().equals(price)) {
            try {
                long cost = LiveCostPolicy.cost(attempt.priceVersion(), usage);
                if (cost <= attempt.reserved()) actual = cost;
            } catch (AgentInvalidException invalidUsage) { /* 非法用量保留全部预留，追加 UNKNOWN。 */ }
        }
        store.finish(attempt, usage, actual, clock.instant());
        if (actual == null) stop(link, snapshot, "OUTPUT_INVALID");
        else if (usage.inputTokens() > LiveCostPolicy.POSTCALL_INPUT_THRESHOLD) stop(link, snapshot, "INPUT_TOKEN_LIMIT");
        String state = actual == null ? "UNKNOWN" : "SETTLED";
        record(snapshot, "agent.daily.finished", Map.of("requestId", request, "state", state,
            "actualMicrousd", actual == null ? "UNKNOWN" : actual));
        return new Outcome(state, actual);
    }
    public void stop(UUID project, UUID request, String code) {
        database.cleanup(() -> {
            var link = prefix(project, request);
            lockCleanupRequest(link);
            stop(link, snapshot(link), code);
            return null;
        });
    }
    /** 设置、授权、原记账日和请求行锁与结果写入同向。CAS 在这些锁内重验当前状态和年龄。 */
    void recoverExpired(UUID project, UUID request, Instant cutoff) {
        database.cleanup(() -> {
            var link = prefix(project, request);
            var attempt = lockCleanupRequest(link);
            var current = snapshot(link);
            var failure = new AgentRequestView.AgentFailure("MODEL_TIMEOUT", AgentApplicationService.message("MODEL_TIMEOUT"));
            if (!requests.expire(request, cutoff, failure, clock.instant())) return null;
            // 只有过期 CAS 的胜者能写 UNKNOWN 或停授权；已有费用事实、预留和槽位保持不动。
            if (attempt.isPresent() && attempt.get().state() == null) {
                store.finish(attempt.get(), null, null, clock.instant());
                record(current, "agent.daily.finished", Map.of("requestId", request, "state", "UNKNOWN",
                    "actualMicrousd", "UNKNOWN"), current.view().traceId());
            }
            stop(link, current, failure.code(), current.view().traceId());
            String state = attempt.map(a -> a.state() == null ? "UNKNOWN" : a.state()).orElse("NOT_ATTEMPTED");
            audit.record(new AuditEvent(current.view().requesterId(), project, "agent.daily.request.failed",
                AuditResult.FAILURE, current.view().traceId(), Map.of("requestId", request, "failureCode", failure.code(),
                    "recovery", true, "accountingState", state)));
            return null;
        });
    }

    private Optional<Attempt> lockCleanupRequest(RequestLink link) {
        var attempt = store.attempt(link.projectId(), link.requestId());
        attempt.ifPresent(existing -> {
            exactAttempt(link, existing);
            lockMoney(existing);
        });
        store.lockRequest(link.projectId(), link.requestId());
        return attempt;
    }
    private void stop(RequestLink link, AgentSnapshot snapshot, String code) {
        stop(link, snapshot, code, traces.currentTraceId());
    }
    private void stop(RequestLink link, AgentSnapshot snapshot, String code, String traceId) {
        if (!store.insertStop(link.projectId(), link.grantId(), link.requestId(), code, clock.instant())) {
            // 同一授权的首次停止原因不可变；原请求不匹配时回滚本次清理。
            var original = store.findStop(link.projectId(), link.grantId()).orElseThrow(DailyAccountingService::invalid);
            if (!link.requestId().equals(original.requestId())) throw invalid();
            return;
        }
        record(snapshot, "agent.daily.grant_stopped", Map.of("requestId", link.requestId(), "grantId", link.grantId(), "code", code), traceId);
    }
    /**
     * 调用方须已按设置→授权→原部署日→原项目日→请求持锁，并在同一事务赢得成功结果 CAS。
     * 此方法只参与该事务，精确清槽的失败会让结果一起回滚。
     */
    public void completeSuccess(UUID project, UUID request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw new AgentUnavailableException();
        var link = store.findRequest(project, request).orElseThrow(DailyAccountingService::invalid);
        var attempt = attempt(link);
        if (!"SETTLED".equals(attempt.state())) throw invalid();
        store.releaseActive(project, link.grantId(), request);
    }

    /** 参与结果事务；先锁原记账日和请求，再用持久化事实复核，已终结行由后续 CAS 自行判负。 */
    AgentSnapshot lockAndValidateSuccess(RequestLink expected, AgentSnapshot supplied, LongSupplier remaining) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw new AgentUnavailableException();
        var link = prefix(expected.projectId(), expected.requestId());
        if (!link.equals(expected)) throw invalid();
        var attempt = attempt(link);
        lockMoney(attempt);
        store.lockRequest(link.projectId(), link.requestId());
        var current = snapshot(link);
        if (!current.input().equals(supplied.input()) || !current.candidates().equals(supplied.candidates())
                || !current.view().bindings().equals(supplied.view().bindings())
                || !current.view().requesterId().equals(supplied.view().requesterId())
                || !current.view().traceId().equals(supplied.view().traceId())
                || !current.view().createdAt().equals(supplied.view().createdAt())
                || !current.view().purpose().equals(supplied.view().purpose())) throw invalid();
        var grant = controls.requireSettled(link, current.view().purpose(), remaining);
        validateBinding(link, grant, current);
        return current;
    }

    private RequestLink prefix(UUID project, UUID request) {
        var link = store.findRequest(project, request).orElseThrow(DailyAccountingService::invalid);
        store.lockSettings(project);
        store.lockUsage(project, link.grantId());
        return link;
    }
    private Attempt attempt(RequestLink link) {
        var attempt = store.attempt(link.projectId(), link.requestId()).orElseThrow(DailyAccountingService::invalid);
        exactAttempt(link, attempt);
        return attempt;
    }
    private void exactAttempt(RequestLink link, Attempt attempt) {
        var grant = store.findGrant(link.projectId(), link.grantId()).orElseThrow(DailyAccountingService::invalid);
        if (!link.requestId().equals(attempt.requestId()) || !link.projectId().equals(attempt.projectId())
                || !link.grantId().equals(attempt.grantId()) || !grant.deploymentId().equals(attempt.deploymentId())
                || !link.credentialVersion().equals(attempt.credentialVersion())
                || link.settingsRevision() != attempt.settingsRevision()
                || !link.runtimeBindingDigest().equals(attempt.runtimeBindingDigest())
                || !LiveCostPolicy.CONTEXT_VERSION.equals(attempt.priceVersion()) || attempt.reserved() != RESERVATION) throw invalid();
    }
    private void lockMoney(Attempt attempt) {
        store.money().lock("DEPLOYMENT", attempt.deploymentId(), attempt.day());
        store.money().lock("PROJECT", attempt.projectId(), attempt.day());
    }
    private AgentSnapshot snapshot(RequestLink link) {
        var snapshot = requests.find(link.projectId(), link.requestId()).orElseThrow(DailyAccountingService::invalid);
        var grant = store.findGrant(link.projectId(), link.grantId()).orElseThrow(DailyAccountingService::invalid);
        if (!snapshot.view().requesterId().equals(grant.ownerId())
                || !link.riskAcknowledgementId().equals(grant.riskAcknowledgementId())
                || !link.payloadSha256().equals(DailySnapshotHashes.sha256(snapshot.input()))
                || !link.candidatesSha256().equals(DailySnapshotHashes.sha256(snapshot.candidates()))) throw invalid();
        return snapshot;
    }
    private void validateRunning(RequestLink link, Grant grant, AgentSnapshot snapshot) {
        if (!"RUNNING".equals(snapshot.view().state())) throw invalid();
        validateBinding(link, grant, snapshot);
    }

    void validateBinding(RequestLink link, Grant grant, AgentSnapshot snapshot) {
        var settings = store.settings(link.projectId());
        var binding = snapshot.view().bindings();
        var profile = AuthorizedAgentSnapshotBuilder.DAILY;
        String prompt = "PR_SUMMARY".equals(snapshot.view().purpose()) ? profile.summaryPrompt() : profile.explanationPrompt();
        if (!link.requestId().equals(snapshot.view().id()) || !link.projectId().equals(snapshot.view().projectId())
                || !snapshot.view().requesterId().equals(grant.ownerId()) || !link.riskAcknowledgementId().equals(grant.riskAcknowledgementId())
                || !link.requestId().equals(grant.usage().activeRequestId())
                || !link.credentialVersion().equals(grant.credentialVersion()) || link.settingsRevision() != settings.revision()
                || !link.runtimeBindingDigest().equals(grant.runtimeBindingDigest())
                || !profile.modelId().equals(binding.modelId()) || !profile.modelProfile().equals(binding.modelProfileVersion())
                || !profile.protocol().equals(binding.modelProtocolVersion()) || !profile.priceVersion().equals(binding.priceCatalogVersion())
                || !prompt.equals(binding.promptVersion()) || !"0.1.0".equals(binding.outputSchemaVersion())) throw invalid();
    }
    private boolean fits(long reserved, long spent, long limit) {
        return reserved >= 0 && spent >= 0 && reserved <= limit && spent <= limit - reserved
            && RESERVATION <= limit - reserved - spent;
    }
    private void limits() {
        if (projectLimit < 0 || projectLimit > 5000000 || deploymentLimit < 0 || deploymentLimit > 20000000)
            throw new AgentUnavailableException();
    }
    private LocalDate today() { return clock.instant().atZone(ZoneOffset.UTC).toLocalDate(); }
    private void record(AgentSnapshot snapshot, String action, Map<String, Object> metadata) {
        record(snapshot, action, metadata, traces.currentTraceId());
    }
    private void record(AgentSnapshot snapshot, String action, Map<String, Object> metadata, String traceId) {
        audit.record(new AuditEvent(snapshot.view().requesterId(), snapshot.view().projectId(), action,
            AuditResult.SUCCESS, traceId, metadata));
    }
    private static AgentDailyStateInvalidException invalid() { return new AgentDailyStateInvalidException(); }
}
