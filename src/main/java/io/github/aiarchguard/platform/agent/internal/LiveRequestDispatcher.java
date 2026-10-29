package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agent.AgentQuotaExceededException;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.common.TraceTaskScope;
import io.github.aiarchguard.platform.identity.AuthenticatedTaskScope;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.identity.InvalidCurrentActorException;
import io.github.aiarchguard.platform.identity.InactiveAuthenticatedTaskException;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 可信进程内调度器：捕获不含 JWT 的身份事实与 trace，提交后排队，领取胜者才执行。无轮询或自动重试；
 * 公开构造器默认拒绝调度。
 */
public final class LiveRequestDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveRequestDispatcher.class);
    private final AgentStore requests;
    private final LiveRequestStore links;
    private final CurrentActorProvider actors;
    private final AuthenticatedTaskScope identities;
    private final TraceTaskScope traces;
    private final LiveDispatchTransitions transitions;
    private final Executor executor;
    private final Execution execution;
    private final Runnable admission;
    private final BoundedExecution boundedExecution;
    private final LongSupplier nanos;

    public LiveRequestDispatcher(AgentStore requests, LiveRequestStore links, CurrentActorProvider actors,
            AuthenticatedTaskScope identities, TraceTaskScope traces, LiveDispatchTransitions transitions,
            LiveExecutionCoordinator execution, Executor executor) {
        this(requests, links, actors, identities, traces, transitions, execution::execute, executor,
            () -> { throw new AgentUnavailableException(); });
    }

    // Compatibility-only test seam; production assembly must select the shared-budget overload.
    LiveRequestDispatcher(AgentStore requests, LiveRequestStore links, CurrentActorProvider actors,
            AuthenticatedTaskScope identities, TraceTaskScope traces, LiveDispatchTransitions transitions,
            Execution execution, Executor executor, Runnable admission) {
        this.requests = requests; this.links = links; this.actors = actors; this.identities = identities;
        this.traces = traces; this.transitions = transitions; this.execution = execution;
        this.executor = executor; this.admission = admission;
        this.boundedExecution = null; this.nanos = System::nanoTime;
    }

    LiveRequestDispatcher(AgentStore requests, LiveRequestStore links, CurrentActorProvider actors,
            AuthenticatedTaskScope identities, TraceTaskScope traces, LiveDispatchTransitions transitions,
            BoundedExecution execution, Executor executor, Runnable admission, LongSupplier nanos) {
        this.requests = requests; this.links = links; this.actors = actors; this.identities = identities;
        this.traces = traces; this.transitions = transitions; this.execution = null;
        this.executor = executor; this.admission = admission;
        this.boundedExecution = java.util.Objects.requireNonNull(execution);
        this.nanos = java.util.Objects.requireNonNull(nanos);
    }

    /** 对外只传播稳定身份/输入错误；其余失败转为不可用，避免执行器或数据库异常穿透 API。 */
    public void dispatch(UUID project, UUID request) {
        try { dispatchTrusted(project, request); }
        catch (AgentInvalidException | AgentNotFoundException | ProjectPermissionDeniedException
                | InvalidCurrentActorException | InactiveAuthenticatedTaskException stable) { throw stable; }
        catch (RuntimeException unavailable) { throw new AgentUnavailableException(); }
    }

    /** 捕获经核验请求者和持久化 trace 的受控作用域；当前有事务时在提交之后才排队，不捕获 JWT 或模型正文。 */
    private void dispatchTrusted(UUID project, UUID request) {
        admission.run();
        if (project == null || request == null) throw new AgentInvalidException();
        var snapshot = requests.find(project, request).orElseThrow(AgentNotFoundException::new);
        var view = snapshot.view();
        if (!actors.currentActor().id().equals(view.requesterId())) throw new ProjectPermissionDeniedException();
        if (!"QUEUED".equals(view.state())) return;
        links.find(project, request).orElseThrow(AgentNotFoundException::new);
        String trace = view.traceId();
        // Only IDs, verified immutable identity facts and trace are captured, never a JWT or request body.
        Runnable scoped = identities.capture(view.requesterId(), traces.wrap(trace, () -> process(project, request)));
        Runnable task = () -> {
            try { scoped.run(); }
            catch (RuntimeException rejectedScope) {
                LOGGER.warn("Live dispatch scope rejected requestId={} traceId={} code={}", request, trace, code(rejectedScope));
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) throw new AgentUnavailableException();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { queue(task); }
            });
        } else {
            if (TransactionSynchronizationManager.isSynchronizationActive()) throw new AgentUnavailableException();
            queue(task);
        }
    }

    /** 排队拒绝只报告不可用；同请求可能已被其他调度副本接收，不能在这里终结它或补发。 */
    private void queue(Runnable task) {
        try { executor.execute(task); }
        catch (RuntimeException rejected) {
            // Another copy may be queued/running. Do not terminalize shared work or retry here.
            throw new AgentUnavailableException();
        }
    }

    /** 从 worker 开始建立一个原始预算，领取和核心共同消耗；只有持有已提交 Claim 的胜者才能执行失败收尾。 */
    private void process(UUID project, UUID request) {
        LiveDispatchTransitions.Claim claim = null;
        try {
            var budget = boundedExecution == null ? null : new LiveExecutionBudget(nanos);
            identities.requireActive();
            claim = budget == null ? transitions.start(project, request) : transitions.start(project, request, budget);
            if (claim == null) return;
            identities.requireActive();
            var link = claim.link();
            if (budget == null) execution.execute(project, link.batchId(), link.templateId(), request);
            else {
                budget.requireRemaining();
                boundedExecution.execute(project, link.batchId(), link.templateId(), request, budget);
            }
        } catch (RuntimeException failure) {
            String code = code(failure);
            if (claim != null) {
                try { transitions.fail(claim, code); }
                catch (RuntimeException persistenceUnavailable) {
                    LOGGER.warn("Live dispatch persistence unavailable requestId={} code=MODEL_UNAVAILABLE", request);
                    return;
                }
            }
            LOGGER.warn("Live dispatch failed requestId={} code={}", request, code);
        }
    }

    private static String code(RuntimeException failure) {
        if (failure instanceof InactiveAuthenticatedTaskException || failure instanceof InvalidCurrentActorException || failure instanceof ProjectPermissionDeniedException
                || failure instanceof ProjectNotFoundException) return "AUTHORIZATION_REVOKED";
        if (failure instanceof LiveResponsePort.CallFailure safe) return safe.code();
        if (failure instanceof AgentQuotaExceededException) return "QUOTA_EXHAUSTED";
        if (failure instanceof AgentUnavailableException) return "MODEL_UNAVAILABLE";
        return "INTERNAL_ERROR";
    }

    @FunctionalInterface interface Execution {
        void execute(UUID project, UUID batch, UUID template, UUID request);
    }

    @FunctionalInterface interface BoundedExecution {
        void execute(UUID project, UUID batch, UUID template, UUID request, LongSupplier remainingNanos);
    }
}
