package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class AgentWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentWorker.class);
    private final AgentStore store;
    private final AgentTransitions transitions;
    private final AgentModelPort model;
    private final AgentOutputValidator validator;
    private final ProjectAuthorization projects;
    private final ReportSubmissionOperations reports;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration providerTimeout;

    AgentWorker(AgentStore store, AgentTransitions transitions, AgentModelPort model,
            AgentOutputValidator validator, ProjectAuthorization projects,
            ReportSubmissionOperations reports, ObjectMapper mapper, Clock clock,
            @Value("${archguard.agent.provider-timeout:30s}") Duration providerTimeout) {
        this.store = store; this.transitions = transitions; this.model = model; this.validator = validator;
        this.projects = projects; this.reports = reports; this.mapper = mapper; this.clock = clock;
        if (providerTimeout.isZero() || providerTimeout.isNegative()
                || providerTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Agent provider timeout must be within (0, 30s]");
        }
        this.providerTimeout = providerTimeout;
    }

    public void process(UUID projectId, UUID requestId) {
        try {
            processOnce(projectId, requestId);
        } catch (RuntimeException unexpected) {
            try {
                var snapshot = store.find(projectId, requestId);
                LOGGER.error("Agent worker failed requestId={} traceId={} errorType={}", requestId,
                    snapshot.map(s -> s.view().traceId()).orElse("unknown"), unexpected.getClass().getSimpleName());
                snapshot.ifPresent(transitions::failUnknown);
            }
            catch (RuntimeException persistenceUnavailable) {
                LOGGER.error("Agent failure could not be persisted requestId={} traceId=unknown errorType={}", requestId,
                    persistenceUnavailable.getClass().getSimpleName());
            }
        }
    }

    private void processOnce(UUID projectId, UUID requestId) {
        AgentSnapshot snapshot = store.find(projectId, requestId).orElse(null);
        if (snapshot == null || !"QUEUED".equals(snapshot.view().state())) return;
        if (Duration.between(snapshot.view().createdAt(), Instant.now(clock)).toSeconds() > 60) {
            transitions.failQueued(snapshot, "MODEL_TIMEOUT");
            return;
        }
        int inputTokens = inputLength(snapshot.input());
        long estimate = inputTokens + 3_000L; // Synthetic catalog: 1 µUSD/input token, 2 µUSD/output token.
        AgentTransitions.Start start = transitions.start(snapshot, estimate);
        if (start == null) return;
        Instant started = Instant.now(clock);
        try {
            projects.requireViewerForActor(projectId, snapshot.view().requesterId());
            if (!prScopeStillValid(snapshot)) {
                fail(snapshot, start, "AUTHORIZATION_REVOKED", started, inputTokens, null, false);
                return;
            }
        } catch (ProjectNotFoundException revoked) {
            fail(snapshot, start, "AUTHORIZATION_REVOKED", started, inputTokens, null, false);
            return;
        }
        if (!model.syntheticOnly() || !model.available()) {
            fail(snapshot, start, "MODEL_UNAVAILABLE", started, inputTokens, null, false);
            return;
        }
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<AgentModelPort.ModelResponse> call = executor.submit(() -> model.explain(snapshot.input()));
        AgentModelPort.ModelResponse response;
        try {
            response = call.get(providerTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            call.cancel(true);
            fail(snapshot, start, "MODEL_TIMEOUT", started, inputTokens, null, true);
            return;
        } catch (InterruptedException interrupted) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            fail(snapshot, start, "MODEL_TIMEOUT", started, inputTokens, null, true);
            return;
        } catch (ExecutionException unavailable) {
            fail(snapshot, start, "MODEL_UNAVAILABLE", started, inputTokens, null, true);
            return;
        } finally {
            executor.shutdownNow();
        }
        if (response == null || response.inputTokens() < 0 || response.inputTokens() > 8000
                || response.outputTokens() < 0 || response.outputTokens() > 1500
                || response.rawJson() == null) {
            fail(snapshot, start, "OUTPUT_INVALID", started, inputTokens, response, false);
            return;
        }
        try {
            AgentRequestView.AgentResult result = validator.validate(response.rawJson(), snapshot);
            projects.requireViewerForActor(projectId, snapshot.view().requesterId());
            if (!prScopeStillValid(snapshot)) {
                fail(snapshot, start, "AUTHORIZATION_REVOKED", started, inputTokens, response, false);
                return;
            }
            transitions.finish(snapshot, start, result, null, usage(started, start.estimate(), response), false);
        } catch (AgentOutputValidator.InvalidOutput invalid) {
            fail(snapshot, start, invalid.code(), started, inputTokens, response, false);
        } catch (ProjectNotFoundException revoked) {
            fail(snapshot, start, "AUTHORIZATION_REVOKED", started, inputTokens, response, false);
        } catch (RuntimeException internal) {
            fail(snapshot, start, "INTERNAL_ERROR", started, inputTokens, response, false);
        }
    }

    private boolean prScopeStillValid(AgentSnapshot snapshot) {
        if (!"PR_SUMMARY".equals(snapshot.view().purpose())) return true;
        var bindings = snapshot.view().bindings();
        return reports.matchesCompletedPrRevisionForActor(snapshot.view().projectId(),
            snapshot.view().requesterId(), bindings.scanJobId(), bindings.reportSha256(),
            bindings.prHeadRevisionId());
    }

    public void failQueued(UUID projectId, UUID requestId, String code) {
        store.find(projectId, requestId).ifPresent(snapshot -> transitions.failQueued(snapshot, code));
    }

    private void fail(AgentSnapshot snapshot, AgentTransitions.Start start, String code, Instant started,
            int inputTokens, AgentModelPort.ModelResponse response, boolean costUnknown) {
        AgentRequestView.AgentUsage usage = response == null
            ? new AgentRequestView.AgentUsage(inputTokens, 0,
                Duration.between(started, Instant.now(clock)).toMillis(), null, start.estimate(),
                costUnknown ? null : 0L, null, null)
            : usage(started, start.estimate(), response);
        transitions.finish(snapshot, start, null,
            new AgentRequestView.AgentFailure(code, AgentApplicationService.message(code)), usage, costUnknown);
    }

    private AgentRequestView.AgentUsage usage(Instant started, long estimate, AgentModelPort.ModelResponse response) {
        long actual = Math.max(0, response.inputTokens()) + Math.max(0, response.outputTokens()) * 2L;
        if (actual > estimate) actual = estimate;
        return new AgentRequestView.AgentUsage(Math.max(0, response.inputTokens()),
            Math.max(0, response.outputTokens()),
            Duration.between(started, Instant.now(clock)).toMillis(), response.latencyMs(), estimate, actual,
            response.providerResponseId(), response.actualModelId());
    }

    private int inputLength(AgentModelPort.ModelInput input) {
        try { return mapper.writeValueAsBytes(input).length; }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Agent input encoding failed", exception); }
    }
}
