package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Local, network-free acceptance fixture. Not an external model or generated advice. */
@Component
@Primary
@Profile("local-compose & agent-synthetic")
@ConditionalOnProperty(name = "archguard.agent.synthetic-enabled", havingValue = "true")
final class LocalSyntheticAgentModel implements AgentModelPort {
    private final ObjectMapper mapper;
    private final String scenario;

    LocalSyntheticAgentModel(ObjectMapper mapper,
            @Value("${archguard.agent.synthetic-scenario:SUPPORTED}") String scenario) {
        if (!Set.of("SUPPORTED", "OUTPUT_INVALID", "CITATION_INVALID", "TIMEOUT", "UNAVAILABLE").contains(scenario)) {
            throw new IllegalArgumentException("Unknown local synthetic scenario");
        }
        this.mapper = mapper;
        this.scenario = scenario;
    }

    @Override public boolean syntheticOnly() { return true; }
    @Override public boolean available() { return true; }

    @Override public ModelResponse explain(ModelInput input) throws Exception {
        if ("TIMEOUT".equals(scenario)) Thread.sleep(35000);
        if ("UNAVAILABLE".equals(scenario)) throw new IllegalStateException("Local synthetic unavailable");
        if ("OUTPUT_INVALID".equals(scenario)) return response("{\"unknown\":true}");
        var findings = "PR_SUMMARY".equals(input.purpose()) ? input.findings()
            : List.of(new FindingInput(input.findingRef(), input.ruleId(), input.ruleVersion(), input.severity(),
                input.message(), input.subject(), input.evidence()));
        ObjectNode output = mapper.createObjectNode();
        output.put("schemaVersion", "0.1.0").put("purpose", input.purpose());
        ArrayNode claims = output.putArray("claims"), rules = output.putArray("ruleBasis"),
            suggestions = output.putArray("suggestions");
        List<String> all = new ArrayList<>();
        for (FindingInput finding : findings) {
            if (finding.evidence().isEmpty()) throw new IllegalArgumentException("Synthetic fixture requires Evidence");
            List<String> citations = new ArrayList<>();
            finding.evidence().forEach(e -> citations.add(e.citationId()));
            input.documents().forEach(d -> citations.add(d.citationId()));
            if ("CITATION_INVALID".equals(scenario)) citations.set(0, "synthetic-fabricated-citation");
            all.addAll(citations);
            ObjectNode claim = claims.addObject().put("findingRef", finding.findingRef())
                .put("text", "Synthetic fixture: the selected Finding has Scanner Evidence.");
            claim.set("citationIds", mapper.valueToTree(citations));
            ObjectNode rule = rules.addObject().put("findingRef", finding.findingRef())
                .put("text", "Synthetic fixture: consult the selected rule and its verified Evidence.");
            rule.set("citationIds", mapper.valueToTree(citations));
            ObjectNode suggestion = suggestions.addObject().put("kind", "HUMAN_VERIFICATION")
                .put("text", "Synthetic fixture: review the dependency boundary manually.")
                .put("requiresHumanReview", true);
            suggestion.set("findingRefs", mapper.valueToTree(List.of(finding.findingRef())));
            suggestion.set("citationIds", mapper.valueToTree(citations));
        }
        ObjectNode conclusion = output.putObject("conclusion").put("kind", "SUPPORTED")
            .put("text", "Synthetic acceptance fixture only; no real model was called.");
        conclusion.set("citationIds", mapper.valueToTree(all.stream().distinct().toList()));
        output.putArray("limitations").add("Fixed local fixture text, tokens and cost; not real model quality or billing.");
        return response(output.toString());
    }

    private ModelResponse response(String raw) {
        return new ModelResponse(raw, 100, 80, 0, "local-synthetic", "local-synthetic-0.1.0");
    }
}
