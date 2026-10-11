package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Internal execution core, deliberately not a bean/worker/HTTP endpoint.
 * Public construction always denies egress; test-only admission has no runtime configuration.
 * Caller must already own the persisted requester's trusted identity context.
 */
public final class LiveExecutionCoordinator {
    private static final long LIMIT_NANOS = Duration.ofSeconds(30).toNanos();
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

    public LiveExecutionCoordinator(AgentStore requests, LiveAccountingOperations accounting, LiveResourceAuthorizer resources,
            LiveResponsePort responses, CredentialUse credentials, LiveResultTransitions transitions,
            AgentOutputValidator validator, CurrentActorProvider actors) {
        this(requests, accounting, resources::validate, responses, credentials,
            transitions, validator, actors, body -> { throw new AgentUnavailableException(); }, System::nanoTime);
    }

    // No production caller/Bean exists; permissive seam is confined to same-package tests.
    LiveExecutionCoordinator(AgentStore requests, LiveAccountingOperations accounting, Consumer<AgentSnapshot> resources,
            LiveResponsePort responses, CredentialUse credentials, LiveResultTransitions transitions,
            AgentOutputValidator validator, CurrentActorProvider actors, SendPolicy policy, LongSupplier nanos) {
        this.requests = requests; this.accounting = accounting; this.resources = resources; this.responses = responses;
        this.credentials = credentials; this.transitions = transitions; this.validator = validator; this.actors = actors;
        this.policy = policy; this.nanos = nanos;
    }

    public void execute(UUID project, UUID batch, UUID template, UUID request) {
        long started = nanos.getAsLong();
        var snapshot = requests.find(project, request).orElseThrow(AgentNotFoundException::new);
        if (!actors.currentActor().id().equals(snapshot.view().requesterId())) throw new ProjectPermissionDeniedException();
        if (!"RUNNING".equals(snapshot.view().state())) return;
        boolean ownsAttempt = false, finalized = false;
        AgentRequestView.AgentUsage usage = null;
        try {
            if (!LiveAccountingOperations.MODEL_PROFILE.equals(snapshot.view().bindings().modelProfileVersion())) throw new AgentEnablementInvalidStateException();
            byte[] body = responses.prepare(snapshot.input());
            remaining(started); policy.require(body.clone()); resources.accept(snapshot);
            remaining(started);
            var admission = accounting.reserve(project, batch, template, request);
            // A replay must not send or terminalize the original in-flight executor's request.
            if (!admission.newAttempt()) return;
            ownsAttempt = true;
            remaining(started);
            var approval = accounting.checkBeforeSend(project, batch, template, request);
            resources.accept(snapshot); policy.require(body.clone()); requireRunning(project, request); remaining(started);
            AtomicBoolean sent = new AtomicBoolean();
            AtomicLong providerLatency = new AtomicLong();
            var reply = credentials.use(approval.credentialVersion(), key -> {
                remaining(started);
                var fresh = accounting.checkBeforeSend(project, batch, template, request);
                if (!fresh.equals(approval)) throw new AgentEnablementInvalidStateException();
                resources.accept(snapshot); policy.require(body.clone()); requireRunning(project, request);
                Duration timeout = remaining(started);
                if (!sent.compareAndSet(false, true)) throw new LiveResponsePort.CallFailure("MODEL_UNAVAILABLE");
                long providerStarted = nanos.getAsLong();
                var response = responses.send(body, key, timeout, approval.allowedResponseModels());
                // Freeze provider time before credential cleanup, ledger waits or output validation.
                providerLatency.set(elapsedMs(providerStarted));
                return response;
            });
            validateAccounting(reply, approval.allowedResponseModels());
            var outcome = accounting.settle(project, request, LiveCostPolicy.VERSION, reply.usage());
            finalized = true;
            if (!"SETTLED".equals(outcome.state()) || outcome.actualMicrousd() == null) throw new LiveResponsePort.CallFailure("OUTPUT_INVALID");
            usage = new AgentRequestView.AgentUsage(reply.usage().inputTokens(), reply.usage().outputTokens(), elapsedMs(started),
                providerLatency.get(), admission.reservedMicrousd(), outcome.actualMicrousd(), reply.providerResponseId(), reply.actualModelId());
            // Late but billable replies are settled, never displayed as successful results.
            remaining(started);
            if (reply.outputFailureCode() != null) throw new AgentOutputValidator.InvalidOutput("OUTPUT_INVALID");
            var result = validator.validate(reply.rawJson(), snapshot);
            resources.accept(snapshot); remaining(started);
            usage = observedUsage(usage, started);
            transitions.finishChecked(snapshot, result, usage, () -> remaining(started));
        } catch (RuntimeException failure) {
            // Preflight denial (including a concurrent replay) never owns a shared RUNNING row.
            // Only the call awarded newAttempt=true may persist its outcome or terminalize work.
            if (!ownsAttempt) throw preflightFailure(failure);
            if (!finalized) {
                // Audit/accounting failure must not refund a possibly incurred charge or trigger a retry.
                try { accounting.unknown(project, request); }
                catch (RuntimeException unavailable) { throw new AgentUnavailableException(); }
            }
            try {
                String code = failureCode(failure);
                usage = observedUsage(usage, started);
                transitions.finish(snapshot, null, new AgentRequestView.AgentFailure(code, AgentApplicationService.message(code)), usage);
            } catch (RuntimeException unavailable) { throw new AgentUnavailableException(); }
        }
    }

    private void requireRunning(UUID project, UUID request) {
        if (requests.find(project, request).filter(s -> "RUNNING".equals(s.view().state())).isEmpty()) throw new AgentEnablementInvalidStateException();
    }
    private Duration remaining(long started) {
        long elapsed = nanos.getAsLong() - started;
        if (Thread.currentThread().isInterrupted() || elapsed < 0 || elapsed >= LIMIT_NANOS) throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
        return Duration.ofNanos(LIMIT_NANOS - elapsed);
    }
    private long elapsedMs(long started) { return Math.max(0, (nanos.getAsLong() - started) / 1_000_000); }
    private AgentRequestView.AgentUsage observedUsage(AgentRequestView.AgentUsage usage, long started) {
        if (usage == null) return null;
        return new AgentRequestView.AgentUsage(usage.inputTokens(), usage.outputTokens(), elapsedMs(started),
            usage.providerLatencyMs(), usage.estimatedCostMicrousd(), usage.actualCostMicrousd(),
            usage.providerResponseId(), usage.actualModelId());
    }
    private void validateAccounting(LiveResponsePort.Reply reply, Set<String> models) {
        if (reply == null || reply.providerResponseId() == null || !reply.providerResponseId().matches("[A-Za-z0-9._:-]{1,128}")
                || reply.actualModelId() == null || !reply.actualModelId().matches("[A-Za-z0-9._:-]{1,128}")
                || !models.contains(reply.actualModelId())) throw new LiveResponsePort.CallFailure("OUTPUT_INVALID");
        try { LiveCostPolicy.cost(reply.usage()); }
        catch (AgentInvalidException invalid) { throw new LiveResponsePort.CallFailure("OUTPUT_INVALID"); }
    }
    private String failureCode(RuntimeException failure) {
        if (failure instanceof LiveResponsePort.CallFailure safe) return safe.code();
        if (failure instanceof AgentOutputValidator.InvalidOutput invalid) return invalid.code();
        if (failure instanceof AgentQuotaExceededException) return "QUOTA_EXHAUSTED";
        if (failure instanceof ProjectPermissionDeniedException || failure instanceof ProjectNotFoundException) return "AUTHORIZATION_REVOKED";
        if (failure instanceof AgentEnablementInvalidStateException) return "MODEL_UNAVAILABLE";
        if (failure instanceof AgentUnavailableException) return "MODEL_UNAVAILABLE";
        return "INTERNAL_ERROR";
    }

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
    /** No runtime implementation yet. Must atomically bind the active slot version; no plaintext return. */
    @FunctionalInterface public interface CredentialUse {
        LiveResponsePort.Reply use(UUID expectedVersion, Function<String, LiveResponsePort.Reply> operation);
    }
}
