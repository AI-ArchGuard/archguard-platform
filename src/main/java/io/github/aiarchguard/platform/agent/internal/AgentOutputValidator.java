package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AgentOutputValidator {
    private static final Pattern EXECUTED_ACTION = Pattern.compile(
        "(?i)\\b(i|we|agent|system)\\s+(have\\s+)?(edited|modified|changed|deleted|merged|committed|pushed|approved)\\b");
    private static final Pattern EXECUTION_DIRECTIVE = Pattern.compile(
        "(?i)\\b(run|execute|delete|merge|commit|push|approve|disable|bypass)\\b|"
            + "(?:自动|立即)(?:修改|删除|合并|提交|执行)");
    private final Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(getClass().getResourceAsStream("/contracts/agent-model-output-0.1.0.schema.json"));
    private final ObjectMapper mapper;

    public AgentOutputValidator(ObjectMapper mapper) { this.mapper = mapper; }

    public AgentRequestView.AgentResult validate(String raw, AgentSnapshot snapshot) {
        if (raw == null || raw.length() > 32768) {
            throw new InvalidOutput("OUTPUT_INVALID");
        }
        try {
            if (!schema.validate(raw, InputFormat.JSON).isEmpty()) throw new InvalidOutput("OUTPUT_INVALID");
        } catch (InvalidOutput invalid) {
            throw invalid;
        } catch (RuntimeException malformed) {
            throw new InvalidOutput("OUTPUT_INVALID");
        }
        JsonNode root;
        try { root = mapper.readTree(raw); }
        catch (Exception exception) { throw new InvalidOutput("OUTPUT_INVALID"); }
        if (!"FINDING_EXPLANATION".equals(root.path("purpose").asText())) throw new InvalidOutput("OUTPUT_INVALID");
        Map<String, AgentSnapshot.Candidate> allowed = new HashMap<>();
        for (AgentSnapshot.Candidate candidate : snapshot.candidates()) {
            if (allowed.put(candidate.citationId(), candidate) != null) throw new InvalidOutput("CITATION_INVALID");
            if (!candidate.projectId().equals(snapshot.view().projectId())) throw new InvalidOutput("CITATION_INVALID");
            if (candidate.scanJobId() != null && (!candidate.scanJobId().equals(snapshot.view().bindings().scanJobId())
                    || !candidate.reportSha256().equals(snapshot.view().bindings().reportSha256()))) {
                throw new InvalidOutput("CITATION_INVALID");
            }
            if (candidate.documentVersionId() != null && snapshot.view().bindings().documentVersions().stream()
                    .noneMatch(v -> v.documentVersionId().equals(candidate.documentVersionId())
                        && v.contentSha256().equals(candidate.contentSha256()))) {
                throw new InvalidOutput("CITATION_INVALID");
            }
        }
        Set<String> cited = new HashSet<>();
        JsonNode conclusion = root.path("conclusion");
        boolean supported = "SUPPORTED".equals(conclusion.path("kind").asText());
        if (supported) {
            checkText(conclusion.path("text").asText(null));
            checkCitations(conclusion.path("citationIds"), allowed, cited, true);
            boolean evidenceBasis = false;
            for (JsonNode id : conclusion.path("citationIds")) {
                if (allowed.get(id.asText()).evidenceId() != null) evidenceBasis = true;
            }
            if (!evidenceBasis) throw new InvalidOutput("OUTPUT_INVALID");
        } else if (!conclusion.path("text").isNull() || !conclusion.path("citationIds").isEmpty()
                || !root.path("claims").isEmpty() || !root.path("ruleBasis").isEmpty()
                || !root.path("suggestions").isEmpty()) {
            throw new InvalidOutput("OUTPUT_INVALID");
        }
        List<String> claims = claims(root.path("claims"), snapshot, allowed, cited, supported);
        List<String> ruleBasis = claims(root.path("ruleBasis"), snapshot, allowed, cited, supported);
        List<AgentRequestView.Suggestion> suggestions = new ArrayList<>();
        for (JsonNode suggestion : root.path("suggestions")) {
            checkText(suggestion.path("text").asText(null));
            if (EXECUTION_DIRECTIVE.matcher(suggestion.path("text").asText()).find()) {
                throw new InvalidOutput("OUTPUT_INVALID");
            }
            if (!suggestion.path("requiresHumanReview").asBoolean()
                    || suggestion.path("findingRefs").size() != 1
                    || !snapshot.input().findingRef().equals(suggestion.path("findingRefs").get(0).asText())) {
                throw new InvalidOutput("OUTPUT_INVALID");
            }
            checkCitations(suggestion.path("citationIds"), allowed, cited, true);
            suggestions.add(new AgentRequestView.Suggestion(suggestion.path("text").asText(),
                suggestion.path("kind").asText(), true));
        }
        if (!supported && (!claims.isEmpty() || !ruleBasis.isEmpty() || !suggestions.isEmpty())) {
            throw new InvalidOutput("OUTPUT_INVALID");
        }
        List<AgentRequestView.VerifiedCitation> verified = snapshot.candidates().stream()
            .filter(c -> cited.contains(c.citationId()))
            .map(c -> new AgentRequestView.VerifiedCitation(c.citationId(), c.source(), c.label(),
                c.projectId(), c.scanJobId(), c.reportSha256(), c.evidenceId(), c.documentVersionId(),
                c.contentSha256(), c.fragmentIndex(), c.fragmentSha256()))
            .toList();
        long evidenceCount = snapshot.candidates().stream().filter(c -> c.evidenceId() != null).count();
        long citedEvidence = verified.stream().filter(c -> c.evidenceId() != null).count();
        String coverage = citedEvidence == 0 ? "NONE" : citedEvidence == evidenceCount ? "COMPLETE" : "PARTIAL";
        return new AgentRequestView.AgentResult(supported ? conclusion.path("text").asText()
            : "Evidence is insufficient for a supported explanation.", ruleBasis, claims, suggestions,
            verified, coverage);
    }

    private List<String> claims(JsonNode items, AgentSnapshot snapshot, Map<String, AgentSnapshot.Candidate> allowed,
            Set<String> cited, boolean supported) {
        List<String> result = new ArrayList<>();
        for (JsonNode claim : items) {
            if (!supported || !snapshot.input().findingRef().equals(claim.path("findingRef").asText())) {
                throw new InvalidOutput("OUTPUT_INVALID");
            }
            checkText(claim.path("text").asText(null));
            checkCitations(claim.path("citationIds"), allowed, cited, true);
            result.add(claim.path("text").asText());
        }
        return List.copyOf(result);
    }

    private void checkCitations(JsonNode ids, Map<String, AgentSnapshot.Candidate> allowed,
            Set<String> cited, boolean required) {
        if (required && ids.isEmpty()) throw new InvalidOutput("OUTPUT_INVALID");
        for (JsonNode id : ids) {
            String value = id.asText();
            if (!allowed.containsKey(value)) throw new InvalidOutput("CITATION_INVALID");
            cited.add(value);
        }
    }

    private void checkText(String value) {
        if (value == null || value.isBlank() || value.length() > 2000 || EXECUTED_ACTION.matcher(value).find()) {
            throw new InvalidOutput("OUTPUT_INVALID");
        }
    }

    public static final class InvalidOutput extends RuntimeException {
        private final String code;
        public InvalidOutput(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
