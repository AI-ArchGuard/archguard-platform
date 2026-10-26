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

/**
 * MVC 完成回调中的治理失败写入审计：只记录已匹配治理路由且响应失败的写方法。
 * 它不覆盖身份层提前拒绝、未进入 MVC 或无法取得当前主体的普通请求，也不替代成功用例的审计。
 * 此回调记录与应用成功事务分开，不能推导所有业务写入与全部失败审计具有整体原子性。
 */
@Component
final class GovernanceWriteAuditInterceptor implements HandlerInterceptor {
    private static final String WEBHOOK_ROUTE = "/api/v1/github/webhooks";
    private static final String REPOSITORY_PREFIX =
        "/api/v1/projects/{projectId}/repositories/{repositoryId}/";
    private static final Set<String> GOVERNANCE_ROOTS = Set.of(
        "baselines", "comparisons", "gate-evaluations", "policy-exceptions", "github", "report-submissions");
    // Webhook 使用专用固定主体关联审计，不能借此把 HMAC 入口解释成已登录用户的操作。
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

    /**
     * 根据实际响应状态追加 governance.write.reject，不读取或保存报告、请求正文、Token 或 Secret。
     * 记录路由模板而不是含具体资源值的请求地址；401/403/404、409 与其他失败分别分类。
     */
    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception exception) {
        // 成功请求和读取请求的失败不属于这条“失败写入”审计。
        if (response.getStatus() < 400 || !("POST".equals(request.getMethod())
                || "PUT".equals(request.getMethod()) || "PATCH".equals(request.getMethod())
                || "DELETE".equals(request.getMethod()))) return;
        // 以 MVC 已选择的路由模板筛选，不能仅凭看起来相似的 URI 将其他模块计入治理审计。
        Object routeAttribute = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(routeAttribute instanceof String route)
                || !(WEBHOOK_ROUTE.equals(route) || isGovernanceRepositoryRoute(route))) return;
        UUID actorId;
        if (WEBHOOK_ROUTE.equals(route)) {
            actorId = GITHUB_ACTOR;
        } else {
            // 普通治理写入必须能取得可信主体；这里不会为缺失身份伪造 actor 或扩大审计覆盖声明。
            try { actorId = actors.currentActor().id(); }
            catch (IllegalStateException | InvalidCurrentActorException ignored) { return; }
        }
        UUID projectId = WEBHOOK_ROUTE.equals(route) ? null : projectId(request.getRequestURI());
        int status = response.getStatus();
        // 此处的 DENIED 只分类已进入该回调的响应，不能声称身份过滤器的所有 401 都会到达这里。
        AuditResult result = status == 409 ? AuditResult.CONFLICT
            : status == 401 || status == 403 || status == 404 ? AuditResult.DENIED : AuditResult.FAILURE;
        audit.record(new AuditEvent(actorId, projectId, "governance.write.reject", result,
            traceIds.currentTraceId(), Map.of("route", route, "method", request.getMethod(),
                "httpStatus", status)));
    }

    /** 从约定 URL 位置提取关联项目；无法解析则保留为空，不把解析成功当作权限证明。 */
    private UUID projectId(String requestUri) {
        String[] segments = requestUri.split("/");
        if (segments.length < 5) return null;
        try { return UUID.fromString(segments[4]); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    /** 仅接受仓库治理前缀下列明的根资源，避免捕获其他仓库子模块的失败。 */
    private boolean isGovernanceRepositoryRoute(String route) {
        if (!route.startsWith(REPOSITORY_PREFIX)) return false;
        String suffix = route.substring(REPOSITORY_PREFIX.length());
        return GOVERNANCE_ROOTS.contains(suffix.split("/", 2)[0]);
    }
}
