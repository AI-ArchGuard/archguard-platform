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

/**
 * 把不可信模型 JSON 转为可展示结果：先验 Schema，再验用途、Finding 范围、引用及建议边界。
 * VerifiedCitation 只从服务端候选重建，文本匹配规则并非完整语义证明。
 */
@Component
public class AgentOutputValidator {
    private static final Pattern EXECUTED_ACTION = Pattern.compile(
        "(?i)\\b(i|we|agent|system)\\s+(have\\s+)?(edited|modified|changed|deleted|merged|committed|pushed|approved)\\b|"
            + "(?:已|已经)(?:自动)?(?:修改|删除|合并|提交|推送|批准|禁用|绕过|执行)");
    private static final Pattern EXECUTION_DIRECTIVE = Pattern.compile(
        "(?i)\\b(run|execute|delete|merge|commit|push|approve|disable|bypass)\\b|"
            + "(?:自动|立即)(?:修改|删除|合并|提交|执行)");
    private final Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(getClass().getResourceAsStream("/contracts/agent-model-output-0.1.0.schema.json"));
    private final ObjectMapper mapper;

    public AgentOutputValidator(ObjectMapper mapper) { this.mapper = mapper; }

    /**
     * 先用冻结 Schema 拒绝非法形状，再核对请求用途、结论、每条主张及建议引用；INSUFFICIENT 不得夹带无依据自由文本。
     */
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
        boolean summary = "PR_SUMMARY".equals(snapshot.view().purpose());
        if (!snapshot.view().purpose().equals(root.path("purpose").asText())
                || !snapshot.view().purpose().equals(snapshot.input().purpose())) {
            throw new InvalidOutput("OUTPUT_INVALID");
        }
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
        Map<String, Set<String>> evidenceByFinding = evidenceByFinding(snapshot, allowed, summary);
        Set<String> selectedRefs = evidenceByFinding.keySet();
        Set<String> cited = new HashSet<>();
        JsonNode conclusion = root.path("conclusion");
        boolean supported = "SUPPORTED".equals(conclusion.path("kind").asText());
        if (supported) {
            checkText(conclusion.path("text").asText(null));
            checkCitations(conclusion.path("citationIds"), allowed, cited, true);
            if (summary) {
                checkScopedCitations(conclusion.path("citationIds"), selectedRefs,
                    evidenceByFinding, allowed);
            } else if (!hasEvidence(conclusion.path("citationIds"), allowed)) {
                throw new InvalidOutput("OUTPUT_INVALID");
            }
        } else if (!conclusion.path("text").isNull() || !conclusion.path("citationIds").isEmpty()
                || !root.path("claims").isEmpty() || !root.path("ruleBasis").isEmpty()
                || !root.path("suggestions").isEmpty()) {
            throw new InvalidOutput("OUTPUT_INVALID");
        }
        Set<String> claimRefs = new HashSet<>(), ruleRefs = new HashSet<>();
        List<String> claims = claims(root.path("claims"), snapshot, allowed, cited,
            supported, summary, evidenceByFinding, claimRefs);
        List<String> ruleBasis = claims(root.path("ruleBasis"), snapshot, allowed, cited,
            supported, summary, evidenceByFinding, ruleRefs);
        if (supported && ruleBasis.isEmpty()) throw new InvalidOutput("OUTPUT_INVALID");
        if (supported && summary && (!claimRefs.equals(selectedRefs) || !ruleRefs.equals(selectedRefs))) {
            throw new InvalidOutput("OUTPUT_INVALID");
        }
        List<AgentRequestView.Suggestion> suggestions = new ArrayList<>();
        for (JsonNode suggestion : root.path("suggestions")) {
            checkText(suggestion.path("text").asText(null));
            if (EXECUTION_DIRECTIVE.matcher(suggestion.path("text").asText()).find()) {
                throw new InvalidOutput("OUTPUT_INVALID");
            }
            Set<String> suggestionRefs = new HashSet<>();
            suggestion.path("findingRefs").forEach(ref -> suggestionRefs.add(ref.asText()));
            if (!suggestion.path("requiresHumanReview").asBoolean()
                    || suggestionRefs.isEmpty()
                    || suggestionRefs.size() != suggestion.path("findingRefs").size()
                    || !selectedRefs.containsAll(suggestionRefs)
                    || (!summary && (suggestionRefs.size() != 1
                        || !suggestionRefs.contains(snapshot.input().findingRef())))) {
                throw new InvalidOutput("OUTPUT_INVALID");
            }
            checkCitations(suggestion.path("citationIds"), allowed, cited, true);
            if (summary) checkScopedCitations(suggestion.path("citationIds"), suggestionRefs,
                evidenceByFinding, allowed);
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

    /**
     * 把每条主张约束到已选 Finding；
     * 摘要还要求引用来自对应 Finding 的 Evidence，不能借另一个 Finding 的证据支撑。
     */
    private List<String> claims(JsonNode items, AgentSnapshot snapshot, Map<String, AgentSnapshot.Candidate> allowed,
            Set<String> cited, boolean supported, boolean summary,
            Map<String, Set<String>> evidenceByFinding, Set<String> refsSeen) {
        List<String> result = new ArrayList<>();
        for (JsonNode claim : items) {
            String ref = claim.path("findingRef").asText();
            if (!supported || !evidenceByFinding.containsKey(ref)
                    || (!summary && !snapshot.input().findingRef().equals(ref))) {
                throw new InvalidOutput("OUTPUT_INVALID");
            }
            checkText(claim.path("text").asText(null));
            checkCitations(claim.path("citationIds"), allowed, cited, true);
            if (summary) checkScopedCitations(claim.path("citationIds"), Set.of(ref),
                evidenceByFinding, allowed);
            refsSeen.add(ref);
            result.add(claim.path("text").asText());
        }
        return List.copyOf(result);
    }

    private Map<String, Set<String>> evidenceByFinding(AgentSnapshot snapshot,
            Map<String, AgentSnapshot.Candidate> allowed, boolean summary) {
        Map<String, Set<String>> result = new HashMap<>();
        if (summary) {
            if (snapshot.input().findings() == null
                    || snapshot.input().findings().size() != snapshot.view().bindings().findingIds().size()) {
                throw new InvalidOutput("OUTPUT_INVALID");
            }
            for (var finding : snapshot.input().findings()) {
                if (finding.findingRef() == null || finding.evidence() == null
                        || result.put(finding.findingRef(), evidenceIds(finding.evidence(), allowed)) != null) {
                    throw new InvalidOutput("OUTPUT_INVALID");
                }
            }
        } else {
            result.put(snapshot.input().findingRef(), evidenceIds(snapshot.input().evidence(), allowed));
        }
        return result;
    }

    private Set<String> evidenceIds(List<io.github.aiarchguard.platform.agent.AgentModelPort.EvidenceInput> inputs,
            Map<String, AgentSnapshot.Candidate> allowed) {
        Set<String> ids = new HashSet<>();
        for (var evidence : inputs) {
            var candidate = allowed.get(evidence.citationId());
            if (candidate == null || candidate.evidenceId() == null || !ids.add(evidence.citationId())) {
                throw new InvalidOutput("CITATION_INVALID");
            }
        }
        return ids;
    }

    /**
     * 检查引用没有跨 Finding 借用 Evidence，并要求每个声称覆盖的 Finding 至少有一条自己的 Evidence。
     */
    private void checkScopedCitations(JsonNode ids, Set<String> refs,
            Map<String, Set<String>> evidenceByFinding, Map<String, AgentSnapshot.Candidate> allowed) {
        for (JsonNode id : ids) {
            var candidate = allowed.get(id.asText());
            if (candidate == null) throw new InvalidOutput("CITATION_INVALID");
            if (candidate.evidenceId() != null && refs.stream()
                    .noneMatch(ref -> evidenceByFinding.get(ref).contains(id.asText()))) {
                throw new InvalidOutput("CITATION_INVALID");
            }
        }
        for (String ref : refs) {
            if (ids.valueStream().noneMatch(id -> evidenceByFinding.get(ref).contains(id.asText()))) {
                throw new InvalidOutput("OUTPUT_INVALID");
            }
        }
    }

    private boolean hasEvidence(JsonNode ids, Map<String, AgentSnapshot.Candidate> allowed) {
        for (JsonNode id : ids) if (allowed.get(id.asText()).evidenceId() != null) return true;
        return false;
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

    /** 限制展示文本并拒绝固定模式的已执行动作声称；这是确定性防线，不保证识别所有同义表达。 */
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
