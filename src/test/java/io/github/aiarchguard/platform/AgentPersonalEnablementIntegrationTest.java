package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import io.github.aiarchguard.platform.agentcredential.CredentialVersionSource;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.persistence.JdbcAuditRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "archguard.agent.credentials.enabled=true", "archguard.agent.credentials.owner-id=11111111-1111-4111-8111-111111111111",
    "archguard.agent.credentials.deployment-id=22222222-2222-4222-8222-222222222222",
    "archguard.agent.credentials.origin=http://localhost:8083", "archguard.agent.credentials.allow-loopback-http=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("local-compose")
@Import(AgentPersonalEnablementIntegrationTest.Config.class)
class AgentPersonalEnablementIntegrationTest extends PostgresIntegrationTestSupport {
    static final String OWNER = "11111111-1111-4111-8111-111111111111";
    static final Instant NOW = Instant.parse("2026-10-06T13:00:00Z");
    static final UUID CREDENTIAL = UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final AtomicReference<CredentialStatus> CREDENTIAL_STATE = new AtomicReference<>();
    static final AtomicReference<Instant> TIME = new AtomicReference<>(NOW);
    static final AtomicReference<String> FAIL_AUDIT_ACTION = new AtomicReference<>();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    UUID project;

    @BeforeEach void prepare() throws Exception {
        TIME.set(NOW);
        FAIL_AUDIT_ACTION.set(null);
        CREDENTIAL_STATE.set(new CredentialStatus(true, CREDENTIAL, NOW));
        String value = mvc.perform(post("/api/v1/projects").with(user(OWNER).authorities(new SimpleGrantedAuthority("project:create")))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("key", "p" + UUID.randomUUID().toString().substring(0, 12), "name", "Synthetic enablement"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        project = UUID.fromString(mapper.readTree(value).path("id").asText());
    }

    String root() { return "/api/v1/projects/" + project + "/agent"; }
    Map<String, Object> input() {
        return Map.ofEntries(Map.entry("schemaVersion", "0.2.0"), Map.entry("scope", "PERSONAL"),
            Map.entry("accountRef", "synthetic-account"), Map.entry("deploymentRef", "synthetic-deployment"),
            Map.entry("expiresAt", NOW.plusSeconds(86400).toString()), Map.entry("sources", List.of(
                source("TERMS", "https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html"),
                source("PRIVACY", "https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html"),
                source("CACHE", "https://api-docs.deepseek.com/guides/kv_cache/"),
                source("MODEL_PRICE", "https://api-docs.deepseek.com/quick_start/pricing/"))),
            Map.entry("unknowns", List.of("PROCESSING_REGION", "STORAGE_REGION", "TRAINING", "HUMAN_REVIEW", "LOG_RETENTION", "CACHE_ISOLATION", "CACHE_RETENTION", "SUBPROCESSORS")),
            Map.entry("riskAccepted", true), Map.entry("modelAlias", "deepseek-flash"), Map.entry("mappingSnapshot", "deepseek-v4.1-flash-2026-10-06"),
            Map.entry("allowedResponseModels", List.of("deepseek-flash")), Map.entry("priceCatalogVersion", "deepseek-flash-peak-usd-2026-10-06"),
            Map.entry("priceCatalogExpiresAt", NOW.plusSeconds(86400).toString()), Map.entry("secretCheckRef", "synthetic-secret-check"),
            Map.entry("networkCheckRef", "synthetic-network-check"), Map.entry("dataScope", "SYNTHETIC_ACCEPTANCE"),
            Map.entry("revocationConditions", List.of("OWNER_REVOKED", "SCOPE_CHANGED", "POLICY_CHANGED", "MODEL_CHANGED", "PRICE_CHANGED", "SECRET_CHECK_INVALID", "NETWORK_CHECK_INVALID")),
            Map.entry("credentialVersion", CREDENTIAL.toString()));
    }
    Map<String, Object> source(String kind, String url) { return Map.of("kind", kind, "url", url, "version", "synthetic-2026-10-06", "checkedAt", NOW.toString()); }
    JsonNode approve() throws Exception {
        return mapper.readTree(mvc.perform(post(root() + "/enablements").with(user(OWNER)).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input())))
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString());
    }

    @Test void defaultsOffAndApprovalCannotInvokeModel() throws Exception {
        mvc.perform(get(root() + "/settings").with(user(OWNER))).andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"0\"")).andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.modelCallsAvailable").value(false));
        JsonNode approval = approve();
        assertThat(approval.path("approvedBy").asText()).isEqualTo(OWNER);
        assertThat(approval.path("credentialVersion").asText()).isEqualTo(CREDENTIAL.toString());
        assertThat(approval.toString()).doesNotContain("accountRef", "networkCheckRef", "secretCheckRef", "sources");
        mvc.perform(put(root() + "/settings").with(user(OWNER)).header("Origin", "http://localhost:8083").header("If-Match", "\"0\"")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("enabled", true, "enablementId", approval.path("id").asText()))))
            .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.modelCallsAvailable").value(false));
        assertThat(jdbc.sql("SELECT count(*) FROM agent.requests WHERE project_id=:id").param("id", project).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.budget_usage WHERE scope_id=:id").param("id", project).query(Integer.class).single()).isZero();
    }

    @Test void optimisticLockAndMissingPreconditionAreStrict() throws Exception {
        mvc.perform(put(root() + "/settings").with(user(OWNER)).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"enablementId\":null}"))
            .andExpect(status().is(428));
        for (int expected : new int[]{200, 412}) {
            mvc.perform(put(root() + "/settings").with(user(OWNER)).header("Origin", "http://localhost:8083").header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"enablementId\":null}"))
                .andExpect(status().is(expected));
        }
    }

    @Test void revokedCrossProjectAndChangedKeyCannotEnable() throws Exception {
        String id = approve().path("id").asText();
        mvc.perform(post(root() + "/enablements/" + id + "/revoke").with(user(OWNER)).header("Origin", "http://localhost:8083"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true));
        mvc.perform(post(root() + "/enablements/" + id + "/revoke").with(user(OWNER)).header("Origin", "http://localhost:8083"))
            .andExpect(status().isOk());
        mvc.perform(put(root() + "/settings").with(user(OWNER)).header("Origin", "http://localhost:8083").header("If-Match", "\"0\"")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("enabled", true, "enablementId", id))))
            .andExpect(status().isConflict());
        id = approve().path("id").asText();
        CREDENTIAL_STATE.set(new CredentialStatus(true, UUID.randomUUID(), NOW));
        mvc.perform(put(root() + "/settings").with(user(OWNER)).header("Origin", "http://localhost:8083").header("If-Match", "\"0\"")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("enabled", true, "enablementId", id))))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/projects/" + UUID.randomUUID() + "/agent/enablements/" + id).with(user(OWNER)))
            .andExpect(status().isNotFound());
    }

    @Test void approvalRequiresOwnerAndProjectMaintainerAndSafeOrigin() throws Exception {
        mvc.perform(get(root() + "/settings")).andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", "no-store"));
        String other = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:a,'MAINTAINER',:t)")
            .param("p", project).param("a", UUID.fromString(other)).param("t", java.sql.Timestamp.from(NOW)).update();
        mvc.perform(post(root() + "/enablements").with(user(other)).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input()))).andExpect(status().isForbidden());
        mvc.perform(post(root() + "/enablements").with(user(OWNER)).header("Origin", "https://hostile.invalid")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input()))).andExpect(status().isForbidden());
        jdbc.sql("UPDATE project.project_members SET role='VIEWER' WHERE project_id=:p AND actor_id=:a")
            .param("p", project).param("a", UUID.fromString(OWNER)).update();
        mvc.perform(get(root() + "/settings").with(user(OWNER))).andExpect(status().isOk());
        mvc.perform(post(root() + "/enablements").with(user(OWNER)).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input()))).andExpect(status().isForbidden());
    }

    @Test void untrustedFieldsAreRejectedWithoutEchoOrStoredApproval() throws Exception {
        String valid = mapper.writeValueAsString(input());
        var wrongModel = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid);
        wrongModel.put("modelAlias", "arbitrary-model");
        for (String value : List.of(valid.replace("\"riskAccepted\":true", "\"riskAccepted\":false"),
                valid.replace("SYNTHETIC_ACCEPTANCE", "PERSONAL_DAILY"), wrongModel.toString(),
                valid.substring(0, valid.length() - 1) + ",\"apiKey\":\"synthetic-forbidden-key\"}", valid + " {}",
                "{\"enabled\":true,\"enabled\":false}", "x".repeat(8193))) {
            String response = mvc.perform(post(root() + "/enablements").with(user(OWNER)).header("Origin", "http://localhost:8083")
                .contentType(MediaType.APPLICATION_JSON).content(value)).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("synthetic-forbidden-key", "apiKey", "arbitrary-model");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM agent.personal_enablements WHERE project_id=:p").param("p", project).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.enablement.write_rejected'")
            .param("p", project).query(Integer.class).single()).isEqualTo(7);
        assertThat(jdbc.sql("SELECT metadata::text FROM audit.audit_records WHERE project_id=:p AND action='agent.enablement.write_rejected'")
            .param("p", project).query(String.class).list()).allSatisfy(value -> assertThat(value)
                .doesNotContain("synthetic-forbidden-key", "apiKey", "accountRef", "sources", "arbitrary-model"));
    }

    @Test void activeRevocationDisablesSettingsOnceAndKeepsImmutableHistory() throws Exception {
        UUID id = UUID.fromString(approve().path("id").asText());
        mvc.perform(put(root() + "/settings").with(user(OWNER)).header("Origin", "http://localhost:8083").header("If-Match", "\"0\"")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("enabled", true, "enablementId", id))))
            .andExpect(status().isOk());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(root() + "/enablements/" + id + "/revoke").with(user(OWNER)).header("Origin", "http://localhost:8083"))
                .andExpect(status().isOk());
            mvc.perform(get(root() + "/settings").with(user(OWNER))).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\"")).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.enablementId").isEmpty());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.enablement.revoked'")
            .param("p", project).query(Integer.class).single()).isOne();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("DELETE FROM agent.enablement_revocations WHERE enablement_id=:id")
            .param("id", id).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("UPDATE agent.enablement_revocations SET revoked_by=:a WHERE enablement_id=:id")
            .param("id", id).param("a", UUID.randomUUID()).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void expiryAndMissingCredentialFailClosedWithoutAlteringHistory() throws Exception {
        String id = approve().path("id").asText();
        TIME.set(NOW.plusSeconds(86400));
        mvc.perform(put(root() + "/settings").with(user(OWNER)).header("Origin", "http://localhost:8083").header("If-Match", "\"0\"")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("enabled", true, "enablementId", id))))
            .andExpect(status().isConflict());
        TIME.set(NOW);
        CREDENTIAL_STATE.set(new CredentialStatus(false, null, null));
        mvc.perform(post(root() + "/enablements").with(user(OWNER)).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input()))).andExpect(status().isConflict());
        mvc.perform(get(root() + "/enablements/" + id).with(user(OWNER))).andExpect(status().isOk())
            .andExpect(jsonPath("$.revoked").value(false));
        assertThat(jdbc.sql("SELECT count(*) FROM agent.personal_enablements WHERE project_id=:p").param("p", project).query(Integer.class).single()).isOne();
    }

    @Test void boundedAcknowledgementRejectsExpiredSourcesAndSecretLikeReferences() throws Exception {
        for (String field : List.of("expiresAt", "priceCatalogExpiresAt", "accountRef", "allowedResponseModels", "sources", "unknowns", "revocationConditions", "credentialVersion")) {
            var body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(input());
            switch (field) {
                case "expiresAt" -> body.put(field, NOW.plusSeconds(8 * 86400).toString());
                case "priceCatalogExpiresAt" -> body.put(field, NOW.toString());
                case "accountRef" -> body.put(field, "sk-synthetic-not-a-key");
                case "allowedResponseModels", "unknowns", "revocationConditions" -> body.putArray(field);
                case "sources" -> ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("sources").get(0)).put("checkedAt", NOW.minusSeconds(8 * 86400).toString());
                case "credentialVersion" -> body.put(field, UUID.randomUUID().toString());
                default -> throw new AssertionError(field);
            }
            mvc.perform(post(root() + "/enablements").with(user(OWNER)).header("Origin", "http://localhost:8083")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().is(field.equals("credentialVersion") ? 409 : 400));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM agent.personal_enablements WHERE project_id=:p").param("p", project).query(Integer.class).single()).isZero();
    }

    @Test void failedSuccessAuditRollsBackApprovalAndSettings() throws Exception {
        FAIL_AUDIT_ACTION.set("agent.enablement.acknowledged");
        mvc.perform(post(root() + "/enablements").with(user(OWNER)).header("Origin", "http://localhost:8083")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input())))
            .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.sql("SELECT count(*) FROM agent.personal_enablements WHERE project_id=:p").param("p", project).query(Integer.class).single()).isZero();
        FAIL_AUDIT_ACTION.set("agent.settings.updated");
        mvc.perform(put(root() + "/settings").with(user(OWNER)).header("Origin", "http://localhost:8083").header("If-Match", "\"0\"")
            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
            .andExpect(status().isServiceUnavailable());
        mvc.perform(get(root() + "/settings").with(user(OWNER))).andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"0\""));
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.enablement.write_rejected'")
            .param("p", project).query(Integer.class).single()).isEqualTo(2);
    }

    @Test void concurrentSettingsWritesCannotLoseUpdates() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> write = () -> {
                ready.countDown();
                if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                return mvc.perform(put(root() + "/settings").with(user(OWNER)).header("Origin", "http://localhost:8083").header("If-Match", "\"0\"")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(write);
            var second = executor.submit(write);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 412);
            mvc.perform(get(root() + "/settings").with(user(OWNER))).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));
        } finally {
            start.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void anotherAuthorizedProjectCannotReadOrSelectAcknowledgement() throws Exception {
        String id = approve().path("id").asText();
        String created = mvc.perform(post("/api/v1/projects").with(user(OWNER).authorities(new SimpleGrantedAuthority("project:create")))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("key", "p" + UUID.randomUUID().toString().substring(0, 12), "name", "Other synthetic project"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID other = UUID.fromString(mapper.readTree(created).path("id").asText());
        String otherRoot = "/api/v1/projects/" + other + "/agent";
        mvc.perform(get(otherRoot + "/enablements/" + id).with(user(OWNER))).andExpect(status().isNotFound());
        mvc.perform(put(otherRoot + "/settings").with(user(OWNER)).header("Origin", "http://localhost:8083").header("If-Match", "\"0\"")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("enabled", true, "enablementId", id))))
            .andExpect(status().isNotFound());
        mvc.perform(post(otherRoot + "/enablements/" + id + "/revoke").with(user(OWNER)).header("Origin", "http://localhost:8083"))
            .andExpect(status().isNotFound());
        mvc.perform(get(root() + "/enablements/" + id).with(user(OWNER))).andExpect(status().isOk())
            .andExpect(jsonPath("$.revoked").value(false));
    }

    @Test void enablementAndRevocationHistoryCannotBeMutatedOrDeleted() throws Exception {
        UUID id = UUID.fromString(approve().path("id").asText());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("UPDATE agent.personal_enablements SET approved_by=:a WHERE id=:id")
            .param("a", UUID.randomUUID()).param("id", id).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("DELETE FROM agent.personal_enablements WHERE id=:id").param("id", id).update())
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        mvc.perform(delete("/api/v1/projects/" + project).with(user(OWNER)).param("version", "0")).andExpect(status().isConflict());
    }

    @TestConfiguration static class Config {
        @Bean @Primary CredentialVersionSource versionSource() { return CREDENTIAL_STATE::get; }
        @Bean @Primary AuditRecorder testAudit(JdbcAuditRecorder delegate) {
            return event -> {
                if (event.action().equals(FAIL_AUDIT_ACTION.get()) && FAIL_AUDIT_ACTION.compareAndSet(event.action(), null)) {
                    throw new AgentUnavailableException();
                }
                delegate.record(event);
            };
        }
        @Bean @Primary Clock testClock() {
            return new Clock() {
                @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(java.time.ZoneId zone) { return Clock.fixed(instant(), zone); }
                @Override public Instant instant() { return TIME.get(); }
            };
        }
    }
}
