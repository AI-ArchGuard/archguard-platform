package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PersonalEnablementContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void proposalIsStrictAndCannotCarrySecretsOrBroaderDataScope() throws Exception {
        try (var input = Files.newInputStream(Path.of("contracts/agent-personal-enablement-0.1.0.schema.json"))) {
            Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(input);
            ObjectNode proposal = proposal();
            assertThat(schema.validate(proposal.toString(), InputFormat.JSON)).isEmpty();
            for (String key : new String[] {"apiKey", "endpoint", "approvedBy", "approvedAt"}) {
                assertThat(schema.validate(proposal.deepCopy().put(key, "synthetic-value").toString(), InputFormat.JSON)).isNotEmpty();
            }
            assertThat(schema.validate(proposal.deepCopy().put("dataScope", "CUSTOMER_SOURCE").toString(), InputFormat.JSON)).isNotEmpty();
            assertThat(schema.validate(proposal.deepCopy().put("riskAccepted", false).toString(), InputFormat.JSON)).isNotEmpty();
            assertThat(schema.validate(proposal.deepCopy().put("modelAlias", "other-model").toString(), InputFormat.JSON)).isNotEmpty();
            var sources = proposal.deepCopy();
            ((ObjectNode) sources.at("/sources/0")).put("kind", "CACHE");
            assertThat(schema.validate(sources.toString(), InputFormat.JSON)).isNotEmpty();
            for (String key : new String[] {"accountRef", "networkCheckRef", "secretCheckRef", "expiresAt", "unknowns", "priceCatalogVersion"}) {
                var missing = proposal.deepCopy(); missing.remove(key);
                assertThat(schema.validate(missing.toString(), InputFormat.JSON)).isNotEmpty();
            }
        }
    }

    private ObjectNode proposal() throws Exception {
        return (ObjectNode) mapper.readTree("""
            {"schemaVersion":"0.1.0","scope":"PERSONAL","accountRef":"synthetic-account",
             "deploymentRef":"synthetic-local","projectId":"11111111-1111-1111-1111-111111111111",
             "expiresAt":"2026-10-07T00:00:00Z","riskAccepted":true,"modelAlias":"deepseek-flash",
             "mappingSnapshot":"synthetic-v4.1-flash","allowedResponseModels":["deepseek-flash"],
             "priceCatalogVersion":"synthetic-unapproved-2026-10-06","priceCatalogExpiresAt":"2026-10-07T00:00:00Z",
             "secretCheckRef":"synthetic-secret-check","networkCheckRef":"synthetic-network-check",
             "dataScope":"SYNTHETIC_ACCEPTANCE","revocationConditions":["OWNER_REVOKED"],
             "unknowns":["PROCESSING_REGION","STORAGE_REGION","TRAINING","HUMAN_REVIEW","LOG_RETENTION","CACHE_ISOLATION","CACHE_RETENTION","SUBPROCESSORS"],
             "sources":[
              {"kind":"TERMS","url":"https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html","version":"synthetic","checkedAt":"2026-10-06T00:00:00Z"},
              {"kind":"PRIVACY","url":"https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html","version":"synthetic","checkedAt":"2026-10-06T00:00:00Z"},
              {"kind":"CACHE","url":"https://api-docs.deepseek.com/guides/kv_cache/","version":"synthetic","checkedAt":"2026-10-06T00:00:00Z"},
              {"kind":"MODEL_PRICE","url":"https://api-docs.deepseek.com/quick_start/pricing/","version":"synthetic","checkedAt":"2026-10-06T00:00:00Z"}]}
            """);
    }
}
