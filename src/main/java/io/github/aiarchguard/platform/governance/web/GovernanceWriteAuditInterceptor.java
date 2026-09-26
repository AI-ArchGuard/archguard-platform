package io.github.aiarchguard.platform.governance.web;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.identity.InvalidCurrentActorException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
final class GovernanceWriteAuditInterceptor implements HandlerInterceptor {
    private static final String WEBHOOK_ROUTE = "/api/v1/github/webhooks";
    private static final String REPOSITORY_PREFIX =
        "/api/v1/projects/{projectId}/repositories/{repositoryId}/";
    private static final Set<String> GOVERNANCE_ROOTS = Set.of(
        "baselines", "comparisons", "gate-evaluations", "policy-exceptions", "github", "report-submissions");
    private static final UUID GITHUB_ACTOR = UUID.nameUUIDFromBytes(
        "archguard:github-webhook".getBytes(StandardCharsets.UTF_8));

    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final CurrentActorProvider actors;

    GovernanceWriteAuditInterceptor(AuditRecorder audit, TraceIdProvider traceIds,
            CurrentActorProvider actors) {
        this.audit = audit;
        this.traceIds = traceIds;
        this.actors = actors;
    }

    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception exception) {
        if (response.getStatus() < 400 || !("POST".equals(request.getMethod())
                || "PUT".equals(request.getMethod()) || "PATCH".equals(request.getMethod())
                || "DELETE".equals(request.getMethod()))) return;
        Object routeAttribute = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(routeAttribute instanceof String route)
                || !(WEBHOOK_ROUTE.equals(route) || isGovernanceRepositoryRoute(route))) return;
        UUID actorId;
        if (WEBHOOK_ROUTE.equals(route)) {
            actorId = GITHUB_ACTOR;
        } else {
            try { actorId = actors.currentActor().id(); }
            catch (IllegalStateException | InvalidCurrentActorException ignored) { return; }
        }
        UUID projectId = WEBHOOK_ROUTE.equals(route) ? null : projectId(request.getRequestURI());
        int status = response.getStatus();
        AuditResult result = status == 409 ? AuditResult.CONFLICT
            : status == 401 || status == 403 || status == 404 ? AuditResult.DENIED : AuditResult.FAILURE;
        audit.record(new AuditEvent(actorId, projectId, "governance.write.reject", result,
            traceIds.currentTraceId(), Map.of("route", route, "method", request.getMethod(),
                "httpStatus", status)));
    }

    private UUID projectId(String requestUri) {
        String[] segments = requestUri.split("/");
        if (segments.length < 5) return null;
        try { return UUID.fromString(segments[4]); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private boolean isGovernanceRepositoryRoute(String route) {
        if (!route.startsWith(REPOSITORY_PREFIX)) return false;
        String suffix = route.substring(REPOSITORY_PREFIX.length());
        return GOVERNANCE_ROOTS.contains(suffix.split("/", 2)[0]);
    }
}
