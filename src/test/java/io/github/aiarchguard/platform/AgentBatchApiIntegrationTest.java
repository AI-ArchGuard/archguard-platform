package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.internal.AgentStore;
import io.github.aiarchguard.platform.agent.internal.LiveAccountingOperations;
import io.github.aiarchguard.platform.agent.AgentEnablementOperations;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"archguard.agent.enabled=true", "archguard.agent.credentials.enabled=true",
    "archguard.agent.credentials.owner-id=11111111-1111-4111-8111-111111111111",
    "archguard.agent.credentials.deployment-id=22222222-2222-4222-8222-222222222222",
    "archguard.agent.credentials.origin=http://localhost:8083", "archguard.agent.credentials.allow-loopback-http=true"})
@AutoConfigureMockMvc
@ActiveProfiles("local-compose")
@Import({AgentPersonalEnablementIntegrationTest.Config.class, AgentLiveAccountingIntegrationTest.Config.class})
class AgentBatchApiIntegrationTest extends PostgresIntegrationTestSupport {
    static final String OWNER = AgentPersonalEnablementIntegrationTest.OWNER;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired AgentEnablementOperations enablements;
    @Autowired AgentStore requests;
    @Autowired LiveAccountingOperations accounting;
    AgentLiveAccountingIntegrationTest fixture;

    @BeforeEach void prepare() throws Exception {
        fixture = new AgentLiveAccountingIntegrationTest();
        fixture.mvc = mvc; fixture.mapper = mapper; fixture.jdbc = jdbc; fixture.enablements = enablements;
        fixture.requests = requests; fixture.accounting = accounting; fixture.prepare();
        SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId");
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId"); }
    String root() { return "/api/v1/projects/" + fixture.project + "/agent/batches"; }
    String previewBody() throws Exception { return mapper.writeValueAsString(Map.of("templateRequestIds", List.of(fixture.template))); }
    JsonNode preview() throws Exception {
        return mapper.readTree(mvc.perform(post(root() + "/preview").with(user(OWNER)).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content(previewBody())).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString());
    }
    String approveBody(String digest) throws Exception {
        return mapper.writeValueAsString(Map.of("enablementId", fixture.acknowledgement, "syntheticInventoryId", UUID.randomUUID(),
            "templateRequestIds", List.of(fixture.template), "manifestSha256", digest, "expiresAt", fixture.now.plusSeconds(3600),
            "maxRequests", 2, "maxCostMicrousd", 8400, "callsApproved", true));
    }
    JsonNode approve() throws Exception {
        String hash = preview().path("manifestSha256").asText(); AgentLiveAccountingIntegrationTest.INVENTORY.add(fixture.project + ":" + hash);
        return mapper.readTree(mvc.perform(post(root()).with(user(OWNER)).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content(approveBody(hash))).andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().exists("Location"))
            .andReturn().getResponse().getContentAsString());
    }
    int count(String table) { return jdbc.sql("SELECT count(*) FROM agent." + table + " WHERE project_id=:p")
        .param("p", fixture.project).query(Integer.class).single(); }

    @Test void previewAndApprovalNeverCreateAttemptsRequestsOrNetworkAndRetainProvenance() throws Exception {
        int before = count("requests"); var preview = preview();
        assertThat(preview.path("schemaVersion").asText()).isEqualTo("0.1.0");
        assertThat(preview.path("templateCount").asInt()).isOne();
        assertThat(preview.toString()).doesNotContain("Synthetic evidence", "payload", "candidates");
        assertThat(count("live_batches")).isZero();
        var batch = approve(); String id = batch.path("id").asText();
        assertThat(batch.path("approvedBy").asText()).isEqualTo(OWNER);
        assertThat(batch.toString()).doesNotContain("reviewRef", "fileSha256", "apiKey");
        assertThat(count("requests")).isEqualTo(before); assertThat(count("live_attempts")).isZero();
        assertThat(jdbc.sql("SELECT inventory_proof->>'fileSha256' FROM agent.live_batches WHERE id=:id")
            .param("id", UUID.fromString(id)).query(String.class).single()).isEqualTo("e".repeat(64));
        mvc.perform(get(root() + "/" + id).with(user(OWNER))).andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(false));
        for (int repeat = 0; repeat < 2; repeat++) mvc.perform(post(root() + "/" + id + "/revoke").with(user(OWNER))
            .header("Origin", "http://localhost:8083")).andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true));
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_batch_revocations WHERE batch_id=:id")
            .param("id", UUID.fromString(id)).query(Integer.class).single()).isOne();
    }
    @Test void authenticationOwnerMaintainerOriginAndForeignProjectAreEnforcedBeforeParsing() throws Exception {
        mvc.perform(post(root() + "/preview").contentType(MediaType.APPLICATION_JSON).content("bad"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        UUID other = UUID.randomUUID();
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:a,'MAINTAINER',NOW())")
            .param("p", fixture.project).param("a", other).update();
        mvc.perform(post(root() + "/preview").with(user(other.toString())).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content("bad")).andExpect(status().isForbidden());
        mvc.perform(post(root() + "/preview").with(user(OWNER)).header("Origin", "https://untrusted.invalid")
            .contentType(MediaType.APPLICATION_JSON).content("bad")).andExpect(status().isForbidden());
        jdbc.sql("UPDATE project.project_members SET role='VIEWER' WHERE project_id=:p AND actor_id=:a")
            .param("p", fixture.project).param("a", UUID.fromString(OWNER)).update();
        mvc.perform(post(root() + "/preview").with(user(OWNER)).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content("bad")).andExpect(status().isForbidden());
        mvc.perform(get(root() + "/" + UUID.randomUUID()).with(user(other.toString())))
            .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
        assertThat(count("live_batches")).isZero(); assertThat(count("live_attempts")).isZero();
    }
    @Test void strictBoundedPreviewAndConsentInputHaveSanitizedAuditedFailures() throws Exception {
        for (String body : List.of("null", "{}", "{\"templateRequestIds\":[]}", previewBody() + "{}",
                "{\"templateRequestIds\":[],\"templateRequestIds\":[]}", "{\"apiKey\":\"synthetic-forbidden-input\"}", "x".repeat(8193),
                mapper.writeValueAsString(Map.of("templateRequestIds", List.of(fixture.template, fixture.template))))) {
            mvc.perform(post(root() + "/preview").with(user(OWNER)).header("Origin", "http://localhost:8083")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"));
        }
        String hash = preview().path("manifestSha256").asText();
        AgentLiveAccountingIntegrationTest.INVENTORY.add(fixture.project + ":" + hash);
        String falseConsent = approveBody(hash).replace("\"callsApproved\":true", "\"callsApproved\":false");
        mvc.perform(post(root()).with(user(OWNER)).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content(falseConsent)).andExpect(status().isBadRequest());
        String audits = jdbc.sql("SELECT metadata::text FROM audit.audit_records WHERE project_id=:p AND action LIKE 'agent.batch.%'")
            .param("p", fixture.project).query(String.class).list().toString();
        assertThat(audits).doesNotContain("synthetic-forbidden-input", "templateRequestIds", "apiKey");
        assertThat(audits).contains("agent.invalid"); assertThat(count("live_batches")).isZero();
    }
    @Test void missingInventoryAuditFailureAndBodyOnRevocationCannotSucceed() throws Exception {
        String hash = preview().path("manifestSha256").asText();
        mvc.perform(post(root()).with(user(OWNER)).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content(approveBody(hash))).andExpect(status().isServiceUnavailable());
        AgentLiveAccountingIntegrationTest.INVENTORY.add(fixture.project + ":" + hash);
        AgentPersonalEnablementIntegrationTest.FAIL_AUDIT_ACTION.set("agent.batch.approved");
        mvc.perform(post(root()).with(user(OWNER)).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content(approveBody(hash))).andExpect(status().isServiceUnavailable());
        assertThat(count("live_batches")).isZero();
        var batch = approve();
        mvc.perform(post(root() + "/" + batch.path("id").asText() + "/revoke").with(user(OWNER)).header("Origin", "http://localhost:8083")
            .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get(root() + "/" + batch.path("id").asText()).with(user(OWNER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(false));
    }

    @Test void foreignProjectAndMalformedIdsAreSanitizedAndBoundaryAuditFailureIsClosed() throws Exception {
        var batch = approve(); UUID foreign = UUID.randomUUID();
        jdbc.sql("INSERT INTO project.projects(id,project_key,name,created_at,created_by) VALUES (:p,:key,'Synthetic other',NOW(),:owner)")
            .param("p", foreign).param("key", "synthetic-" + foreign.toString().substring(0, 8)).param("owner", UUID.fromString(OWNER)).update();
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:a,'MAINTAINER',NOW())")
            .param("p", foreign).param("a", UUID.fromString(OWNER)).update();
        mvc.perform(get("/api/v1/projects/" + foreign + "/agent/batches/" + batch.path("id").asText()).with(user(OWNER)))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("agent.not_found"))
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(root() + "/not-a-uuid").with(user(OWNER)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("agent.invalid"))
            .andExpect(header().string("Cache-Control", "no-store"));
        AgentPersonalEnablementIntegrationTest.FAIL_AUDIT_ACTION.set("agent.batch.http_rejected");
        String response = mvc.perform(post(root() + "/preview").with(user(OWNER)).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content("synthetic-private-parser-input"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("agent.unavailable"))
            .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("synthetic-private-parser-input", "Exception", "not-a-uuid");
        assertThat(count("live_attempts")).isZero();
    }
}
