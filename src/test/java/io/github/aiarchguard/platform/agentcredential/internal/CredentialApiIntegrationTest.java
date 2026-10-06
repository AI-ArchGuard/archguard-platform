package io.github.aiarchguard.platform.agentcredential.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.aiarchguard.platform.ArchGuardPlatformApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(classes = ArchGuardPlatformApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("local-compose")
class CredentialApiIntegrationTest {
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.11-alpine")
        .withDatabaseName("credential_test").withUsername("test").withPassword("test-only-password");
    static final UUID OWNER = UUID.randomUUID();
    static final Path ROOT;
    static final Path DIRECTORY;
    static final Path MASTER;
    static {
        try {
            ROOT = Files.createTempDirectory("archguard-credential-tests-");
            DIRECTORY = Files.createDirectory(ROOT.resolve("encrypted")); PrivateCredentialFiles.restrict(DIRECTORY, true);
            MASTER = Files.write(ROOT.resolve("master"), new byte[32]); PrivateCredentialFiles.restrict(MASTER, false);
            DB.start();
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    static final String ROUTE = "/api/v1/agent/credentials/deepseek";
    static final String SYNTHETIC = "synthetic-test-only-credential";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("archguard.runner.enabled", () -> false);
        registry.add("archguard.agent.credentials.enabled", () -> true);
        registry.add("archguard.agent.credentials.owner-id", OWNER::toString);
        registry.add("archguard.agent.credentials.deployment-id", OWNER::toString);
        registry.add("archguard.agent.credentials.origin", () -> "http://localhost:8082");
        registry.add("archguard.agent.credentials.allow-loopback-http", () -> true);
        registry.add("archguard.agent.credentials.directory", DIRECTORY::toString);
        registry.add("archguard.agent.credentials.master-file", MASTER::toString);
    }
    @BeforeEach void reset() throws Exception { Files.deleteIfExists(DIRECTORY.resolve("deepseek.credential")); }

    @Test void onlyMetadataReturnedAndAuditContainsNoCredentials() throws Exception {
        String response = mvc.perform(put(ROUTE).with(user(OWNER.toString())).header("Origin", "http://localhost:8082")
            .contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"" + SYNTHETIC + "\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SYNTHETIC, "apiKey", "master", "directory");
        assertThat(mapper.readTree(response).path("configured").asBoolean()).isTrue();
        String read = mvc.perform(get(ROUTE).with(user(OWNER.toString())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(read).isEqualTo(response);
        assertThat(jdbc.sql("SELECT metadata::text FROM audit.audit_records WHERE actor_id=:id")
            .param("id", OWNER).query(String.class).list()).allSatisfy(value -> assertThat(value)
                .doesNotContain(SYNTHETIC, "apiKey", "master", DIRECTORY.toString()));
        assertThat(jdbc.sql("SELECT count(*) FROM agent.requests").query(Integer.class).single()).isZero();
        mvc.perform(delete(ROUTE).with(user(OWNER.toString())).header("Origin", "http://localhost:8082"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(delete(ROUTE).with(user(OWNER.toString())).header("Origin", "http://localhost:8082"))
            .andExpect(status().isOk());
        assertThat(Files.exists(DIRECTORY.resolve("deepseek.credential"))).isFalse();
    }

    @Test void unauthenticatedWrongOwnerAndHostileOriginCannotWrite() throws Exception {
        mvc.perform(get(ROUTE)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(put(ROUTE).with(user(UUID.randomUUID().toString())).header("Origin", "http://localhost:8082")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"));
        for (String origin : new String[]{"http://hostile.invalid", "null"}) {
            mvc.perform(delete(ROUTE).with(user(OWNER.toString())).header("Origin", origin)).andExpect(status().isForbidden());
        }
        mvc.perform(delete(ROUTE).with(user(OWNER.toString()))).andExpect(status().isForbidden());
        assertThat(Files.exists(DIRECTORY.resolve("deepseek.credential"))).isFalse();
    }

    @Test void malformedDuplicateUnknownFieldsAndOversizeAreSanitized() throws Exception {
        for (String json : new String[]{"{", "null", "{\"apiKey\":\"" + SYNTHETIC + "\",\"url\":\"bad\"}",
                "{\"apiKey\":\"" + SYNTHETIC + "\",\"apiKey\":\"another\"}",
                "{\"apiKey\":\"" + SYNTHETIC + "\"} {}", "{\"apiKey\":\"" + "a".repeat(600) + "\"}"}) {
            String error = mvc.perform(put(ROUTE).with(user(OWNER.toString())).header("Origin", "http://localhost:8082")
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
            assertThat(error).doesNotContain(SYNTHETIC, "another", "apiKey");
        }
        assertThat(Files.exists(DIRECTORY.resolve("deepseek.credential"))).isFalse();
    }
}
