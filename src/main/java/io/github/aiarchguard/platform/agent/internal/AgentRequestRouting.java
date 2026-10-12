package io.github.aiarchguard.platform.agent.internal;

import org.springframework.stereotype.Component;

/** Keep the legacy synthetic worker and ledger away from separately approved live requests. */
@Component
public final class AgentRequestRouting {
    private final LiveRequestStore links;

    public AgentRequestRouting(LiveRequestStore links) { this.links = links; }

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
