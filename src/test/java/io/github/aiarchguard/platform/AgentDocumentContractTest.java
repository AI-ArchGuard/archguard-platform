package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 离线验证项目文档 API 的版本、路径范围和检索上限，以及不可变版本的按 ID 读取契约。这里不上传文件或调用检索运行时。
 */
class AgentDocumentContractTest {
    @Test
    // 文档接口只承担版本与检索职责，固定版本数量和返回片段上限。
    void documentOpenApiIsVersionedBoundedAndHasResolvableReferences() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Path.of("openapi", "agent-documents-v1.json").toFile());
        assertThat(root.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(root.path("info").path("version").asText()).isEqualTo("0.1.0");
        assertThat(root.path("paths").size()).isEqualTo(5);
        assertThat(root.at("/paths/~1api~1v1~1projects~1{projectId}~1documents~1versions~1{versionId}/get/operationId").asText())
            .isEqualTo("getProjectDocumentVersionById");
        assertThat(root.path("paths").toString()).doesNotContain("agent/requests", "gate-evaluations", "baselines");
        assertThat(root.at("/components/schemas/SearchRequest/properties/versionIds/maxItems").asInt()).isEqualTo(10);
        assertThat(root.at("/components/schemas/SearchResponse/properties/items/maxItems").asInt()).isEqualTo(4);
        List<String> references = new ArrayList<>();
        collect(root, references);
        for (String reference : references) {
            assertThat(reference).startsWith("#/components/");
            assertThat(root.at(reference.substring(1)).isMissingNode()).isFalse();
        }
    }

    // 递归核对组件引用，确保嵌套请求和响应也没有悬空定义。
    private static void collect(JsonNode node, List<String> references) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            if ("$ref".equals(entry.getKey())) references.add(entry.getValue().asText());
            else collect(entry.getValue(), references);
        });
        else if (node.isArray()) node.forEach(value -> collect(value, references));
    }
}
