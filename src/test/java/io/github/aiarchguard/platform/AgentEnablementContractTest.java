package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 离线对照个人确认 Schema、DTO 与设置 API，锁定严格字段和默认关闭语义。配置可读不代表 Key 可读或真实模型已经启用。
 */
class AgentEnablementContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    // 合法确认与逐项非法变异同时校验，防止 Schema 与运行时字段分离。
    @Test void acknowledgementSchemaMatchesRuntimeAndRejectsUntrustedFields() throws Exception {
        var path = Path.of("contracts/agent-personal-enablement-0.2.0.schema.json");
        try (var stream = Files.newInputStream(path)) {
            var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(stream);
            var fixture = new AgentPersonalEnablementIntegrationTest();
            ObjectNode body = mapper.valueToTree(fixture.input());
            assertThat(schema.validate(body.toString(), InputFormat.JSON)).isEmpty();
            body.put("apiKey", "synthetic-forbidden-key");
            assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty();
            body.remove("apiKey");
            body.put("accountRef", "sk-synthetic-not-a-key");
            assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty();
            body.put("accountRef", "synthetic-account");
            body.put("riskAccepted", false);
            assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty();
            body.put("riskAccepted", true);
            body.put("approvedBy", "synthetic-self-reported-actor");
            assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty();
        }
    }

    // 增量设置入口保持乐观锁和凭据不回读，所有本地引用须解析成功。
    @Test void additiveApiHasLockedSettingsNoKeyReadAndResolvableReferences() throws Exception {
        var file = Path.of("openapi/agent-enablement-v1.json");
        JsonNode api = mapper.readTree(file.toFile());
        assertThat(api.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(api.path("info").path("version").asText()).isEqualTo("0.4.0");
        assertThat(api.path("x-archguard-request-body-max-bytes").asInt()).isEqualTo(8192);
        assertThat(api.path("paths").size()).isEqualTo(4);
        assertThat(api.at("/components/schemas/Settings/properties/enabled/default").asBoolean()).isFalse();
        assertThat(api.at("/components/schemas/Settings/properties/modelCallsAvailable/type").asText()).isEqualTo("boolean");
        assertThat(api.at("/components/schemas/Settings/properties/modelCallsAvailable/default").asBoolean()).isFalse();
        assertThat(api.at("/components/schemas/Settings/properties/modelCallsAvailable").has("const")).isFalse();
        assertThat(java.util.stream.StreamSupport.stream(api.at("/components/schemas/Settings/properties/unavailableReason/enum")
            .spliterator(), false).anyMatch(JsonNode::isNull)).isTrue();
        assertThat(api.at("/components/schemas/Enablement/properties").toString()).doesNotContain("apiKey", "accountRef", "networkCheckRef", "secretCheckRef", "sources");
        assertThat(api.path("paths").path("/api/v1/projects/{projectId}/agent/settings").path("put").path("responses").has("428")).isTrue();
        assertThat(api.path("paths").path("/api/v1/projects/{projectId}/agent/settings").path("put").path("responses").has("412")).isTrue();
        resolve(api, api, file);
        for (String version : new String[] {"0.2.0", "0.3.0", "0.4.0"}) {
            var schemaFile = Path.of("contracts/agent-personal-enablement-" + version + ".schema.json");
            JsonNode body = mapper.readTree(schemaFile.toFile());
            resolve(body, body, schemaFile);
        }
    }
    // 按本地契约位置递归解析引用，不执行外部获取。
    private void resolve(JsonNode value, JsonNode root, Path file) throws Exception {
        if (value.isObject()) {
            if (value.has("$ref")) {
                String ref = value.path("$ref").asText();
                if (ref.startsWith("#/")) assertThat(root.at(ref.substring(1)).isMissingNode()).isFalse();
                else {
                    assertThat(ref).isIn("../contracts/agent-personal-enablement-0.2.0.schema.json",
                        "../contracts/agent-personal-enablement-0.3.0.schema.json",
                        "../contracts/agent-personal-enablement-0.4.0.schema.json");
                    assertThat(Files.isRegularFile(file.getParent().resolve(ref).normalize())).isTrue();
                }
            }
            var fields = value.elements();
            while (fields.hasNext()) resolve(fields.next(), root, file);
        } else if (value.isArray()) for (JsonNode child : value) resolve(child, root, file);
    }
}
