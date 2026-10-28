package io.github.aiarchguard.platform.agent.internal;

import org.springframework.stereotype.Component;

/**
 * 保护原合成 worker 和预算的路由判定；
 * 同时核对完整模型/价格/Prompt 绑定与 live 关联，不能仅凭某个看似合成的字段选路。
 */
@Component
public final class AgentRequestRouting {
    private final LiveRequestStore links;

    public AgentRequestRouting(LiveRequestStore links) { this.links = links; }

    /** 同时核对用途与完整模型绑定，最后排除持久化 live 关联；失败返回 false，不能猜测采用旧预算。 */
    public boolean synthetic(AgentSnapshot snapshot) {
        if (snapshot == null || snapshot.view() == null || snapshot.view().bindings() == null || snapshot.input() == null) return false;
        var view = snapshot.view();
        var bindings = view.bindings();
        var input = snapshot.input();
        String prompt = switch (view.purpose() == null ? "" : view.purpose()) {
            case "FINDING_EXPLANATION" -> "finding-explanation-0.1.0";
            case "PR_SUMMARY" -> "pr-summary-0.1.0";
            default -> null;
        };
        if (prompt == null || view.projectId() == null || view.id() == null
                || !"disabled-or-test-fake-0.1.0".equals(bindings.modelProfileVersion())
                || !"responses-v1-restricted".equals(bindings.modelProtocolVersion())
                || !"deterministic-fake-or-disabled".equals(bindings.modelId())
                || !"synthetic-0.1.0".equals(bindings.priceCatalogVersion())
                || !"0.1.0".equals(bindings.outputSchemaVersion()) || !"0.1.0".equals(input.schemaVersion())
                || !view.purpose().equals(input.purpose()) || !prompt.equals(bindings.promptVersion())
                || !prompt.equals(input.promptVersion())) return false;
        // A binding-looking request with an immutable live association is still never synthetic work.
        return links.find(view.projectId(), view.id()).isEmpty();
    }
}
