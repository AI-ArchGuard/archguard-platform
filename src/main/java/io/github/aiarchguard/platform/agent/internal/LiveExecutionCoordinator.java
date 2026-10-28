package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.identity.InactiveAuthenticatedTaskException;
import io.github.aiarchguard.platform.identity.InvalidCurrentActorException;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 真实响应执行核心，要求调用者已具有请求者的可信身份作用域。串接资源复核、唯一费用尝试、版本绑定凭据、单次发送与独立结算；
 * 公开构造器默认拒绝外发，受控装配才选择准入。
 */
public final class LiveExecutionCoordinator {
    private final AgentStore requests;
    private final LiveAccountingOperations accounting;
    private final Consumer<AgentSnapshot> resources;
    private final LiveResponsePort responses;
    private final CredentialUse credentials;
    private final LiveResultTransitions transitions;
    private final AgentOutputValidator validator;
    private final CurrentActorProvider actors;
    private final SendPolicy policy;
    private final LongSupplier nanos;
    private final LiveDatabaseWork database;
    private final Runnable requireActive;

    public LiveExecutionCoordinator(AgentStore requests, LiveAccountingOperations accounting, LiveResourceAuthorizer resources,
            LiveResponsePort responses, CredentialUse credentials, LiveResultTransitions transitions,
            AgentOutputValidator validator, CurrentActorProvider actors) {
        this(requests, accounting, resources::validate, responses, credentials,
            transitions, validator, actors, body -> { throw new AgentUnavailableException(); }, System::nanoTime,
            new LiveDatabaseWork() {});
    }

    public LiveExecutionCoordinator(AgentStore requests, LiveAccountingOperations accounting, LiveResourceAuthorizer resources,
            LiveResponsePort responses, CredentialUse credentials, LiveResultTransitions transitions,
            AgentOutputValidator validator, CurrentActorProvider actors, LiveDatabaseWork database) {
        this(requests, accounting, resources::validate, responses, credentials,
            transitions, validator, actors, body -> { throw new AgentUnavailableException(); }, System::nanoTime, database);
    }

    // Compatibility-only test seam; production selects the overload with explicit identity rechecks.
    LiveExecutionCoordinator(AgentStore requests, LiveAccountingOperations accounting, Consumer<AgentSnapshot> resources,
            LiveResponsePort responses, CredentialUse credentials, LiveResultTransitions transitions,
            AgentOutputValidator validator, CurrentActorProvider actors, SendPolicy policy, LongSupplier nanos,
            LiveDatabaseWork database) {
        this(requests, accounting, resources, responses, credentials, transitions, validator, actors, policy, nanos, database, () -> {});
    }

    LiveExecutionCoordinator(AgentStore requests, LiveAccountingOperations accounting, Consumer<AgentSnapshot> resources,
            LiveResponsePort responses, CredentialUse credentials, LiveResultTransitions transitions,
            AgentOutputValidator validator, CurrentActorProvider actors, SendPolicy policy, LongSupplier nanos,
            LiveDatabaseWork database, Runnable requireActive) {
        this.requests = requests; this.accounting = accounting; this.resources = resources; this.responses = responses;
        this.credentials = credentials; this.transitions = transitions; this.validator = validator; this.actors = actors;
        this.policy = policy; this.nanos = nanos; this.database = java.util.Objects.requireNonNull(database);
        this.requireActive = java.util.Objects.requireNonNull(requireActive);
    }

    void execute(UUID project, UUID batch, UUID template, UUID request, LongSupplier remainingNanos) {
        execute(project, batch, template, request, new LiveExecutionBudget(nanos, remainingNanos));
    }

    public void execute(UUID project, UUID batch, UUID template, UUID request) {
        execute(project, batch, template, request, new LiveExecutionBudget(nanos));
    }

    /**
     * 只在无外层事务且可信身份匹配时处理 RUNNING 请求；
     * 取得新 attempt 后才拥有失败收尾权，HTTP、用量结算和结果事务彼此分离。
     */
    private void execute(UUID project, UUID batch, UUID template, UUID request, LiveExecutionBudget budget) {
        requireNoDatabaseTransaction();
        long started = nanos.getAsLong();
        LongSupplier remaining = budget;
        java.util.Optional<AgentSnapshot> selected;
        try { requireActive.run(); selected = database.transaction(remaining, true, () -> requests.find(project, request)); }
        catch (RuntimeException failure) { throw preflightFailure(failure); }
        var snapshot = selected.orElseThrow(AgentNotFoundException::new);
        if (!actors.currentActor().id().equals(snapshot.view().requesterId())) throw new ProjectPermissionDeniedException();
        if (!"RUNNING".equals(snapshot.view().state())) return;
        boolean ownsAttempt = false, finalized = false;
        AgentRequestView.AgentUsage usage = null;
        try {
            String price = snapshot.view().bindings().priceCatalogVersion();
            if (!LiveCostPolicy.modelProfile(price).equals(snapshot.view().bindings().modelProfileVersion())) throw new AgentEnablementInvalidStateException();
            byte[] body = responses.prepare(snapshot.input());
            budget.requireRemaining(); requireActive.run(); policy.require(body.clone()); validateResources(snapshot, remaining);
            budget.requireRemaining(); requireActive.run();
            var admission = accounting.reserve(project, batch, template, request, remaining);
            // A replay must not send or terminalize the original in-flight executor's request.
            // 重放者没有尝试所有权：既不发送，也不替原执行者写终态。
            if (!admission.newAttempt()) return;
            ownsAttempt = true;
            budget.requireRemaining(); requireActive.run();
            var approval = accounting.checkBeforeSend(project, batch, template, request, remaining);
            validateResources(snapshot, remaining); policy.require(body.clone()); requireRunning(project, request, remaining);
            budget.requireRemaining(); requireActive.run();
            AtomicBoolean sent = new AtomicBoolean();
            AtomicBoolean preflightStarted = new AtomicBoolean(), preflightCompleted = new AtomicBoolean();
            AtomicBoolean consumerActive = new AtomicBoolean(true), consumerViolated = new AtomicBoolean();
            // 单独保存真实传输返回值，防止凭据消费回调的错误返回/清理异常覆盖计费用量。
            AtomicReference<LiveResponsePort.Reply> received = new AtomicReference<>();
            Thread caller = Thread.currentThread();
            AtomicLong providerLatency = new AtomicLong();
            LiveResponsePort.Reply consumerReply = null;
            boolean consumerFailed = false;
            try {
                consumerReply = credentials.use(approval.credentialVersion(), remaining, () -> {
                    if (!consumerActive.get() || Thread.currentThread() != caller || !preflightStarted.compareAndSet(false, true)) {
                        consumerViolated.set(true);
                        throw new LiveResponsePort.CallFailure("MODEL_UNAVAILABLE");
                    }
                    budget.requireRemaining(); requireActive.run();
                    var fresh = accounting.checkBeforeSend(project, batch, template, request, remaining);
                    if (!fresh.equals(approval)) throw new AgentEnablementInvalidStateException();
                    validateResources(snapshot, remaining); policy.require(body.clone()); requireRunning(project, request, remaining);
                    budget.requireRemaining(); requireActive.run();
                    preflightCompleted.set(true);
                }, key -> {
                    // This callback may hold the credential slot lock. Only the in-memory identity
                    // capability check is permitted here; Project/resource/accounting SQL stays outside.
                    if (!consumerActive.get() || Thread.currentThread() != caller || !preflightCompleted.get() || consumerViolated.get()) {
                        consumerViolated.set(true);
                        throw new LiveResponsePort.CallFailure("MODEL_UNAVAILABLE");
                    }
                    requireNoDatabaseTransaction();
                    requireActive.run();
                    Duration timeout = budget.requireRemaining();
                    if (!sent.compareAndSet(false, true)) {
                        consumerViolated.set(true);
                        throw new LiveResponsePort.CallFailure("MODEL_UNAVAILABLE");
                    }
                    long providerStarted = nanos.getAsLong();
                    var response = responses.send(body, key, timeout, approval.allowedResponseModels(), price);
                    // Capture the actual transport reply before any consumer cleanup or return handling.
                    received.set(response);
                    // Freeze provider time before credential cleanup, ledger waits or output validation.
                    providerLatency.set(elapsedMs(providerStarted));
                    return response;
                });
            } catch (RuntimeException failure) {
                if (received.get() == null) throw failure;
                consumerFailed = true;
            } finally {
                // A retained callback is unusable after this synchronous consumption scope closes.
                consumerActive.set(false);
            }
            var reply = received.get();
            if (reply == null) throw new LiveResponsePort.CallFailure(sent.get() ? "OUTPUT_INVALID" : "MODEL_UNAVAILABLE");
            if (consumerFailed || consumerViolated.get() || !preflightCompleted.get() || consumerReply != reply) {
                // Interface failure cannot replace an actual reply or erase independently billable usage.
                reply = new LiveResponsePort.Reply(null, "OUTPUT_INVALID", reply.usage(), reply.providerResponseId(), reply.actualModelId());
            }
            validateAccounting(reply, approval.allowedResponseModels(), price);
            // 合法用量先以独立事务结算；后续输出拒绝、撤权或成功结果回滚都不能退回这笔已发生费用。
            var outcome = accounting.settle(project, request, price, reply.usage());
            finalized = true;
            if (!"SETTLED".equals(outcome.state()) || outcome.actualMicrousd() == null) throw new LiveResponsePort.CallFailure("OUTPUT_INVALID");
            usage = new AgentRequestView.AgentUsage(reply.usage().inputTokens(), reply.usage().outputTokens(), elapsedMs(started),
                providerLatency.get(), admission.reservedMicrousd(), outcome.actualMicrousd(), reply.providerResponseId(), reply.actualModelId());
            // Late but billable replies are settled, never displayed as successful results.
            budget.requireRemaining(); requireActive.run();
            // 8,000 是调用后停批阈值，超过也先记合法费用，再拒绝展示成功；不是发送前 Token 证明。
            if (LiveCostPolicy.isContext(price) && reply.usage().inputTokens() > LiveCostPolicy.POSTCALL_INPUT_THRESHOLD) {
                throw new AgentOutputValidator.InvalidOutput("OUTPUT_INVALID");
            }
            if (reply.outputFailureCode() != null) throw new AgentOutputValidator.InvalidOutput("OUTPUT_INVALID");
            var result = validator.validate(reply.rawJson(), snapshot);
            validateResources(snapshot, remaining); budget.requireRemaining(); requireActive.run();
            usage = observedUsage(usage, started);
            transitions.finishBounded(snapshot, result, usage, remaining);
        } catch (RuntimeException failure) {
            // Preflight denial (including a concurrent replay) never owns a shared RUNNING row.
            // Only the call awarded newAttempt=true may persist its outcome or terminalize work.
            // 尚未拥有 newAttempt 的预检失败不能污染共享请求或账本。
            if (!ownsAttempt) throw preflightFailure(failure);
            if (LiveCostPolicy.isContext(snapshot.view().bindings().priceCatalogVersion())) {
                try { accounting.stopBatch(project, batch, request, failureCode(failure)); }
                catch (RuntimeException unavailable) { throw new AgentUnavailableException(); }
            }
            // 没有可信结算的失败保守记 UNKNOWN，保留预留且不自动重试。
            if (!finalized) {
                // Audit/accounting failure must not refund a possibly incurred charge or trigger a retry.
                try { accounting.unknown(project, request); }
                catch (RuntimeException unavailable) { throw new AgentUnavailableException(); }
            }
            try {
                String code = failureCode(failure);
                usage = observedUsage(usage, started);
                transitions.failBounded(snapshot, new AgentRequestView.AgentFailure(code, AgentApplicationService.message(code)), usage);
            } catch (RuntimeException unavailable) { throw new AgentUnavailableException(); }
        }
    }

    private void requireRunning(UUID project, UUID request, LongSupplier remaining) {
        var current = database.transaction(remaining, true, () -> requests.find(project, request));
        if (current.filter(s -> "RUNNING".equals(s.view().state())).isEmpty()) throw new AgentEnablementInvalidStateException();
    }
    private void validateResources(AgentSnapshot snapshot, LongSupplier remaining) {
        database.transaction(remaining, true, () -> { resources.accept(snapshot); return null; });
    }
    /**
     * REQUIRES_NEW 会恢复调用方外层事务，因此核心和最终发送都拒绝事务/同步作用域，避免数据库事务跨越网络等待。
     */
    private static void requireNoDatabaseTransaction() {
        // REQUIRES_NEW restores a caller's suspended transaction. Never let that surround HTTP.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) throw new AgentUnavailableException();
    }
    private long elapsedMs(long started) { return Math.max(0, (nanos.getAsLong() - started) / 1_000_000); }
    private AgentRequestView.AgentUsage observedUsage(AgentRequestView.AgentUsage usage, long started) {
        if (usage == null) return null;
        return new AgentRequestView.AgentUsage(usage.inputTokens(), usage.outputTokens(), elapsedMs(started),
            usage.providerLatencyMs(), usage.estimatedCostMicrousd(), usage.actualCostMicrousd(),
            usage.providerResponseId(), usage.actualModelId());
    }
    /** 响应 ID、模型和用量都是不可信输入；先核对固定模型集合与价格策略，合法用量才可送独立账本结算。 */
    private void validateAccounting(LiveResponsePort.Reply reply, Set<String> models, String price) {
        if (reply == null || reply.providerResponseId() == null || !reply.providerResponseId().matches("[A-Za-z0-9._:-]{1,128}")
                || reply.actualModelId() == null || !reply.actualModelId().matches("[A-Za-z0-9._:-]{1,128}")
                || !models.contains(reply.actualModelId())) throw new LiveResponsePort.CallFailure("OUTPUT_INVALID");
        try { LiveCostPolicy.cost(price, reply.usage()); }
        catch (AgentInvalidException invalid) { throw new LiveResponsePort.CallFailure("OUTPUT_INVALID"); }
    }
    private String failureCode(RuntimeException failure) {
        if (failure instanceof LiveResponsePort.CallFailure safe) return safe.code();
        if (failure instanceof AgentOutputValidator.InvalidOutput invalid) return invalid.code();
        if (failure instanceof AgentQuotaExceededException) return "QUOTA_EXHAUSTED";
        if (failure instanceof ProjectPermissionDeniedException || failure instanceof ProjectNotFoundException
                || failure instanceof InactiveAuthenticatedTaskException || failure instanceof InvalidCurrentActorException) return "AUTHORIZATION_REVOKED";
        if (failure instanceof AgentEnablementInvalidStateException) return "MODEL_UNAVAILABLE";
        if (failure instanceof AgentUnavailableException) return "MODEL_UNAVAILABLE";
        return "INTERNAL_ERROR";
    }

    /**
     * 尚未取得 attempt 的预检失败只转换稳定异常；既不回传适配器敏感 cause，也不能终结其他执行者的共享请求。
     */
    private RuntimeException preflightFailure(RuntimeException failure) {
        // Never propagate an adapter/parser/resource exception or its potentially sensitive cause.
        return switch (failureCode(failure)) {
            case "AUTHORIZATION_REVOKED" -> new ProjectPermissionDeniedException();
            case "QUOTA_EXHAUSTED" -> new AgentQuotaExceededException();
            case "MODEL_TIMEOUT" -> new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
            case "OUTPUT_INVALID", "CITATION_INVALID" -> new AgentOutputValidator.InvalidOutput(failureCode(failure));
            default -> new AgentUnavailableException();
        };
    }

    @FunctionalInterface interface SendPolicy { void require(byte[] encodedRequest); }
    /**
     * 在调用线程上先于凭据槽位锁运行一次 preflight，再原子绑定当前版本并执行一次 finalSend。
     * 两个回调在 use 返回后失效；finalSend 只做本地身份能力检查和传输，不进入需要数据库的模块。
     * 明文只能在同步消费作用域内使用，返回值必须保留该次实际响应，不能返回 Key。
     */
    @FunctionalInterface public interface CredentialUse {
        LiveResponsePort.Reply use(UUID expectedVersion, Runnable preflight, Function<String, LiveResponsePort.Reply> finalSend);

        /** A remaining budget from the original clock; unsupported consumers must never fall back to unbounded use. */
        default LiveResponsePort.Reply use(UUID expectedVersion, LongSupplier remainingNanos, Runnable preflight,
                Function<String, LiveResponsePort.Reply> finalSend) {
            throw new LiveResponsePort.CallFailure("MODEL_UNAVAILABLE");
        }
    }
}
