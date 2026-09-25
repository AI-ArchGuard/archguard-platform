package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "archguard.github.webhook-secret=test-webhook-secret")
@AutoConfigureMockMvc
class GovernanceCiApiIntegrationTest extends PostgresIntegrationTestSupport {
    private static final String ACTOR = "11111111-1111-1111-1111-111111111111";
    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    @Autowired GovernanceCiApiIntegrationTest(MockMvc mvc, JdbcClient jdbc, ObjectMapper mapper) {
        this.mvc = mvc; this.jdbc = jdbc; this.mapper = mapper;
    }

    @Test void signedWebhookAndCiSubmissionCloseNewViolationThenRepairGate() throws Exception {
        Fixture fixture = fixture(true);
        String base = path(fixture);
        mvc.perform(put(base + "/github/link").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"providerRepositoryId\":\"123\",\"ownerName\":\"AI-ArchGuard\",\"repositoryName\":\"demo\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.providerRepositoryId").value("123"));
        byte[] clean = emptyReport(fixture.identity()).getBytes(StandardCharsets.UTF_8);
        JsonNode initial = response(submit(fixture, "clean-baseline", "a".repeat(40), null, clean)
            .andExpect(status().isAccepted()));
        assertThat(initial.path("status").asText()).isEqualTo("COMPLETED");
        mvc.perform(get(base + "/report-submissions/" + initial.path("id").asText() + "/gate-evaluation")
            .with(user(ACTOR))).andExpect(status().isOk()).andExpect(jsonPath("$.ciExitCode").value(64));
        UUID baselineJob = jdbc.sql("SELECT scan_job_id FROM governance.report_submissions WHERE id=:id")
            .param("id", UUID.fromString(initial.path("id").asText())).query(UUID.class).single();
        mvc.perform(post(base + "/baselines").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"targetBranch\":\"main\",\"ruleSetVersionId\":\""
                + fixture.rules() + "\",\"scanJobId\":\"" + baselineJob + "\",\"commitSha\":\""
                + "a".repeat(40) + "\"}")).andExpect(status().isCreated());

        Instant firstAt = Instant.now().minusSeconds(3);
        UUID firstDelivery = UUID.randomUUID();
        byte[] firstPayload = webhook("b".repeat(40), firstAt);
        webhook(firstDelivery, firstPayload, signature(firstPayload)).andExpect(status().isOk())
            .andExpect(jsonPath("$.disposition").value("APPLIED"));
        webhook(firstDelivery, firstPayload, signature(firstPayload)).andExpect(status().isOk())
            .andExpect(jsonPath("$.replay").value(true));
        byte[] violation = violationReport(fixture.identity()).getBytes(StandardCharsets.UTF_8);
        JsonNode failed = response(submit(fixture, "pr-violation", "b".repeat(40), "7", violation)
            .andExpect(status().isAccepted()));
        String failedId = failed.path("id").asText();
        mvc.perform(get(base + "/report-submissions/" + failedId + "/gate-evaluation").with(user(ACTOR)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FAIL"))
            .andExpect(jsonPath("$.ciExitCode").value(2))
            .andExpect(jsonPath("$.counts.NEW").value(1));
        mvc.perform(get(base + "/github/pull-requests/7").with(user(ACTOR)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.headSha").value("b".repeat(40)))
            .andExpect(jsonPath("$.currentGateEvaluationId").value(failed.path("gateEvaluationId").asText()));
        submit(fixture, "pr-violation", "b".repeat(40), "7", violation)
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(failedId));

        Instant secondAt = Instant.now().minusSeconds(1);
        byte[] secondPayload = webhook("c".repeat(40), secondAt);
        webhook(UUID.randomUUID(), secondPayload, signature(secondPayload)).andExpect(status().isOk())
            .andExpect(jsonPath("$.disposition").value("APPLIED"));
        mvc.perform(get(base + "/github/pull-requests/7").with(user(ACTOR)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.headSha").value("c".repeat(40)))
            .andExpect(jsonPath("$.currentGateEvaluationId").doesNotExist());
        byte[] latePayload = webhook("b".repeat(40), firstAt);
        webhook(UUID.randomUUID(), latePayload, signature(latePayload)).andExpect(status().isOk())
            .andExpect(jsonPath("$.disposition").value("STALE"));
        JsonNode repaired = response(submit(fixture, "pr-repair", "c".repeat(40), "7", clean)
            .andExpect(status().isAccepted()));
        mvc.perform(get(base + "/report-submissions/" + repaired.path("id").asText() + "/gate-evaluation")
            .with(user(ACTOR))).andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("PASS"))
            .andExpect(jsonPath("$.ciExitCode").value(0));
        mvc.perform(get(base + "/github/pull-requests/7").with(user(ACTOR)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.headSha").value("c".repeat(40)))
            .andExpect(jsonPath("$.currentGateEvaluationId").value(repaired.path("gateEvaluationId").asText()));
        assertThat(jdbc.sql("SELECT count(*) FROM governance.report_submissions WHERE project_id=:project")
            .param("project", fixture.project()).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM scanjob.scan_jobs WHERE project_id=:project AND idempotency_key LIKE 'ci:%'")
            .param("project", fixture.project()).query(Long.class).single()).isEqualTo(3);
    }

    @Test void badSignatureDeliveryCollisionAndCrossProjectWriteFailClosed() throws Exception {
        Fixture own = fixture(true), other = fixture(false);
        byte[] payload = webhook("a".repeat(40), Instant.now(), 999999);
        UUID delivery = UUID.randomUUID();
        webhook(delivery, payload, "sha256=" + "0".repeat(64)).andExpect(status().isUnauthorized());
        webhook(delivery, payload, signature(payload)).andExpect(status().isOk())
            .andExpect(jsonPath("$.disposition").value("UNLINKED"));
        byte[] changed = webhook("b".repeat(40), Instant.now(), 999999);
        webhook(delivery, changed, signature(changed)).andExpect(status().isConflict());
        mvc.perform(put(path(other) + "/github/link").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"providerRepositoryId\":\"999\",\"ownerName\":\"other\",\"repositoryName\":\"demo\"}"))
            .andExpect(status().isNotFound());
        byte[] clean = emptyReport(own.identity()).getBytes(StandardCharsets.UTF_8);
        submit(other, "cross-project", "a".repeat(40), null, clean).andExpect(status().isNotFound());
    }

    private Fixture fixture(boolean member) {
        UUID project = UUID.randomUUID(), repository = UUID.randomUUID(), ruleSet = UUID.randomUUID(), rules = UUID.randomUUID();
        String identity = "test:" + project;
        jdbc.sql("INSERT INTO project.projects(id,project_key,name,created_at,created_by) VALUES (:id,:key,'Governance CI',NOW(),:actor)")
            .param("id", project).param("key", "p" + project.toString().replace("-", "").substring(0, 12))
            .param("actor", UUID.fromString(ACTOR)).update();
        if (member) jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:project,:actor,'MAINTAINER',NOW())")
            .param("project", project).param("actor", UUID.fromString(ACTOR)).update();
        jdbc.sql("""
            INSERT INTO repository.repositories(id,project_id,repository_key,name,mount_path,scanner_identity,created_by,created_at)
            VALUES (:id,:project,:key,'Repository','/tmp/archguard-test',:identity,:actor,NOW())
            """).param("id", repository).param("project", project)
            .param("key", "r" + repository.toString().replace("-", "").substring(0, 12))
            .param("identity", identity).param("actor", UUID.fromString(ACTOR)).update();
        jdbc.sql("""
            INSERT INTO ruleset.rule_sets(id,project_id,repository_id,rule_set_key,name,created_by,created_at)
            VALUES (:id,:project,:repository,'governance-ci','Governance CI',:actor,NOW())
            """).param("id", ruleSet).param("project", project).param("repository", repository)
            .param("actor", UUID.fromString(ACTOR)).update();
        jdbc.sql("""
            INSERT INTO ruleset.rule_set_versions(id,rule_set_id,version_number,yaml_content,content_sha256,
              scanner_version,rules_schema_version,created_by,created_at)
            VALUES (:id,:set,1,'rules: []',:sha,'0.2.1','0.1.0',:actor,NOW())
            """).param("id", rules).param("set", ruleSet).param("sha", "0".repeat(64))
            .param("actor", UUID.fromString(ACTOR)).update();
        return new Fixture(project, repository, rules, identity);
    }
    private ResultActions submit(Fixture fixture, String key, String commit, String pr, byte[] report) throws Exception {
        var revision = Map.of("provider", "github", "providerRepositoryId", "123",
            "commitSha", commit, "targetBranch", "main");
        var metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("ruleSetVersionId", fixture.rules().toString());
        metadata.put("revision", revision);
        if (pr != null) metadata.put("pullRequest", Map.of("externalId", pr,
            "headSha", commit, "baseSha", "a".repeat(40)));
        metadata.put("scannerVersion", "0.2.1"); metadata.put("schemaVersion", "0.1.0");
        metadata.put("reportSha256", sha256(report));
        var metadataPart = new MockMultipartFile("metadata", "metadata.json", "application/json",
            mapper.writeValueAsBytes(metadata));
        var reportPart = new MockMultipartFile("report", "report.json", "application/json", report);
        return mvc.perform(multipart(path(fixture) + "/report-submissions")
            .file(metadataPart).file(reportPart).with(user(ACTOR)).with(csrf()).header("Idempotency-Key", key));
    }
    private ResultActions webhook(UUID delivery, byte[] payload, String signature) throws Exception {
        return mvc.perform(post("/api/v1/github/webhooks").header("X-GitHub-Delivery", delivery)
            .header("X-GitHub-Event", "pull_request").header("X-Hub-Signature-256", signature)
            .contentType(MediaType.APPLICATION_JSON).content(payload));
    }
    private byte[] webhook(String head, Instant at) throws Exception {
        return webhook(head, at, 123);
    }
    private byte[] webhook(String head, Instant at, int providerRepositoryId) throws Exception {
        return mapper.writeValueAsBytes(Map.of("action", "synchronize", "number", 7,
            "repository", Map.of("id", providerRepositoryId), "pull_request", Map.of("updated_at", at.toString(),
                "head", Map.of("sha", head), "base", Map.of("sha", "a".repeat(40), "ref", "main"))));
    }
    private static String signature(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private JsonNode response(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
    private static String path(Fixture f) {
        return "/api/v1/projects/" + f.project() + "/repositories/" + f.repository();
    }
    private String emptyReport(String identity) throws Exception {
        ObjectNode report = fullReport(identity);
        ((ArrayNode) report.path("findings")).removeAll();
        return mapper.writeValueAsString(report);
    }
    private String violationReport(String identity) throws Exception {
        ObjectNode report = fullReport(identity);
        ((ArrayNode) report.path("findings")).remove(1);
        ((ObjectNode) report.path("findings").get(0).path("extensions"))
            .putArray("archguard.violation").add("layer-violation");
        return mapper.writeValueAsString(report);
    }
    private ObjectNode fullReport(String identity) throws Exception {
        try (var input = getClass().getResourceAsStream("/reports/full-report.json")) {
            ObjectNode report = (ObjectNode) mapper.readTree(input);
            ((ObjectNode) report.path("project")).put("identity", identity);
            return report;
        }
    }
    private record Fixture(UUID project, UUID repository, UUID rules, String identity) { }
}
