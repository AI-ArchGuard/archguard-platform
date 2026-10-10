package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import java.util.List;
import java.util.UUID;

public record LiveManifest(String schemaVersion, UUID projectId, List<Template> templates) {
    public LiveManifest { templates = List.copyOf(templates); }
    public record Selection(String purpose, UUID scanJobId, String reportSha256, UUID prHeadRevisionId,
            List<UUID> findingIds, List<AgentRequestView.DocumentVersionBinding> documentVersions, String outputSchemaVersion) { }
    public record Template(UUID templateRequestId, Selection selection, String payloadSha256, String candidatesSha256) { }
    static Selection selection(AgentSnapshot snapshot) {
        var view = snapshot.view(); var value = view.bindings();
        if (value == null || snapshot.input() == null || view.purpose() == null || value.findingIds() == null || value.documentVersions() == null
                || !List.of("FINDING_EXPLANATION", "PR_SUMMARY").contains(view.purpose())
                || !view.purpose().equals(snapshot.input().purpose()) || !"0.1.0".equals(value.outputSchemaVersion())
                || !"0.1.0".equals(snapshot.input().schemaVersion())) throw new AgentInvalidException();
        return new Selection(view.purpose(), value.scanJobId(), value.reportSha256(), value.prHeadRevisionId(),
            value.findingIds().stream().sorted().toList(), value.documentVersions().stream()
                .sorted(java.util.Comparator.comparing(AgentRequestView.DocumentVersionBinding::documentVersionId)).toList(), value.outputSchemaVersion());
    }
    static String payload(ObjectMapper mapper, AgentSnapshot snapshot) {
        ObjectNode body = mapper.valueToTree(snapshot.input());
        // The live prompt is versioned separately. All actual data and citation handles stay pinned.
        body.remove("promptVersion");
        return hash(mapper, body);
    }
    static String hash(ObjectMapper mapper, Object value) {
        try { return AgentApplicationService.sha256(mapper.writeValueAsString(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new AgentInvalidException(); }
    }
}
