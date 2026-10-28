package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import java.util.List;
import java.util.UUID;

/**
 * 批次模板的不可变清单结构；把资源选择、输入摘要和候选摘要分别固定。
 * 真实 Prompt 独立绑定，移除该版本字段不意味着允许改变其他输入。
 */
public record LiveManifest(String schemaVersion, UUID projectId, List<Template> templates) {
    public LiveManifest { templates = List.copyOf(templates); }
    public record Selection(String purpose, UUID scanJobId, String reportSha256, UUID prHeadRevisionId,
            List<UUID> findingIds, List<AgentRequestView.DocumentVersionBinding> documentVersions, String outputSchemaVersion) { }
    public record Template(UUID templateRequestId, Selection selection, String payloadSha256, String candidatesSha256) { }
    /** 对资源 ID 和文档版本排序，得到可重算的选择投影；绑定内容而非用户提交的任意摘要。 */
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
    /** 只剔除独立版本化的 Prompt 字段，其余实际数据与随机引用句柄仍固定到批准清单。 */
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
