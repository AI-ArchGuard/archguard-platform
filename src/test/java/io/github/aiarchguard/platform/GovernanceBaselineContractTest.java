package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/**
 * 离线读取治理基线 OpenAPI，锁定运行时路径、分类枚举和本地引用。它校验契约文件形状，不执行基线创建或分类业务。
 */
class GovernanceBaselineContractTest {
    // 逐个解析组件引用，并固定 NEW/EXISTING/RESOLVED 分类语义。
    @Test void runtimeContractHasResolvableReferencesAndFixedClassifications() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Path.of("openapi", "governance-baselines-v1.json").toFile());
        assertThat(root.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(root.path("x-archguard-lifecycle").asText()).isEqualTo("runtime-3c");
        assertThat(root.path("paths").size()).isEqualTo(4);
        List<String> refs = new ArrayList<>();
        collect(root, refs);
        for (String ref : refs) {
            assertThat(ref).startsWith("#/components/");
            assertThat(root.at(ref.substring(1)).isMissingNode()).isFalse();
        }
        assertThat(StreamSupport.stream(root.at("/components/schemas/ClassifiedFinding/properties/classification/enum")
            .spliterator(), false).map(JsonNode::asText).toList())
            .containsExactly("NEW", "EXISTING", "RESOLVED");
        assertThat(root.at("/components/schemas/BaselineVersion/properties/fingerprintVersion/const").asText())
            .isEqualTo("platform-finding-v1");
    }
    // 递归收集对象和数组中的引用，避免遗漏嵌套组件。
    private static void collect(JsonNode node, List<String> refs) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            if ("$ref".equals(entry.getKey())) refs.add(entry.getValue().asText());
            else collect(entry.getValue(), refs);
        });
        else if (node.isArray()) node.forEach(value -> collect(value, refs));
    }
}
