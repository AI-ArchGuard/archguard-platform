package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GovernanceApiIntegrationTest extends PostgresIntegrationTestSupport {
    private static final String ACTOR = "11111111-1111-1111-1111-111111111111";
    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    @Autowired GovernanceApiIntegrationTest(MockMvc mvc, JdbcClient jdbc, ObjectMapper mapper) {
        this.mvc = mvc; this.jdbc = jdbc; this.mapper = mapper;
    }

    @Test void promotesImmutableVersionsAndClassifiesNewAndResolved() throws Exception {
        Fixture fixture = fixture(true);
        UUID firstJob = job(fixture, emptyReport(fixture.identity()));
        String base = path(fixture);
        JsonNode first = mapper.readTree(mvc.perform(post(base + "/baselines").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(promote(fixture, firstJob, "a".repeat(40))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.active").value(true)).andReturn().getResponse().getContentAsString());
        UUID firstVersion = UUID.fromString(first.path("id").asText());

        mvc.perform(post(base + "/baselines").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(promote(fixture, firstJob, "a".repeat(40))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.selectionVersion").value(2));

        UUID candidate = job(fixture, violationReport(fixture.identity()));
        mvc.perform(post(base + "/comparisons").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(compare(fixture, candidate)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.newCount").value(1))
            .andExpect(jsonPath("$.existingCount").value(0))
            .andExpect(jsonPath("$.resolvedCount").value(0))
            .andExpect(jsonPath("$.findings[0].classification").value("NEW"));

        mvc.perform(post(base + "/baselines").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(promote(fixture, candidate, "b".repeat(40))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(post(base + "/comparisons").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(compare(fixture, firstJob)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.newCount").value(0))
            .andExpect(jsonPath("$.resolvedCount").value(1))
            .andExpect(jsonPath("$.findings[0].classification").value("RESOLVED"));
        mvc.perform(get(base + "/baselines/active").with(user(ACTOR))
            .param("targetBranch", "main").param("ruleSetVersionId", fixture.rules().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(post(base + "/baselines/" + firstVersion + "/select").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(scope(fixture)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.selectionVersion").value(4));
        assertThat(jdbc.sql("SELECT count(*) FROM governance.baseline_versions WHERE scope_id IN (SELECT id FROM governance.baseline_scopes WHERE project_id=:project)")
            .param("project", fixture.project()).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:project AND action LIKE 'governance.%'")
            .param("project", fixture.project()).query(Long.class).single()).isEqualTo(6);
    }

    @Test void concealsCrossProjectBaselinesAndRejectsWrongRuleSetVersion() throws Exception {
        Fixture own = fixture(true);
        Fixture other = fixture(false);
        UUID ownJob = job(own, emptyReport(own.identity()));
        String otherPath = path(other);
        mvc.perform(get(otherPath + "/baselines").with(user(ACTOR))
            .param("targetBranch", "main").param("ruleSetVersionId", other.rules().toString()))
            .andExpect(status().isNotFound());
        mvc.perform(post(otherPath + "/baselines").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(promote(other, ownJob, "a".repeat(40))))
            .andExpect(status().isNotFound());
        mvc.perform(post(path(own) + "/baselines").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(promote(own, ownJob, "a".repeat(40))))
            .andExpect(status().isCreated());
        mvc.perform(post(path(own) + "/baselines").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(promote(own.withRules(other.rules()), ownJob, "a".repeat(40))))
            .andExpect(status().isNotFound());
    }

    @Test void gatePersistsImmutableFailureAndExceptionAllowsOnlyNewEvaluation() throws Exception {
        Fixture fixture = fixture(true);
        UUID baselineJob = job(fixture, emptyReport(fixture.identity()));
        UUID candidate = job(fixture, violationReport(fixture.identity()));
        String base = path(fixture);
        mvc.perform(post(base + "/baselines").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(promote(fixture, baselineJob, "a".repeat(40))))
            .andExpect(status().isCreated());
        String request = compare(fixture, candidate);
        JsonNode failed = mapper.readTree(mvc.perform(post(base + "/gate-evaluations")
            .with(user(ACTOR)).with(csrf()).header("Idempotency-Key", "gate-one")
            .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("FAIL"))
            .andExpect(jsonPath("$.ciExitCode").value(2))
            .andExpect(jsonPath("$.newCount").value(1))
            .andExpect(jsonPath("$.blockedCount").value(1))
            .andReturn().getResponse().getContentAsString());
        UUID gateId = UUID.fromString(failed.path("id").asText());
        mvc.perform(post(base + "/gate-evaluations").with(user(ACTOR)).with(csrf())
            .header("Idempotency-Key", "gate-one").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(gateId.toString()));
        JsonNode compared = mapper.readTree(mvc.perform(post(base + "/comparisons")
            .with(user(ACTOR)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String fingerprint = compared.path("findings").get(0).path("fingerprint").asText();
        Instant now = Instant.now();
        String waiver = "{\"targetBranch\":\"main\",\"ruleSetVersionId\":\"" + fixture.rules()
            + "\",\"scopeType\":\"FINGERPRINT\",\"scopeValue\":\"" + fingerprint
            + "\",\"reason\":\"Reviewed temporary exception\",\"effectiveAt\":\""
            + now.minusSeconds(5) + "\",\"expiresAt\":\"" + now.plusSeconds(3600) + "\"}";
        JsonNode exception = mapper.readTree(mvc.perform(post(base + "/policy-exceptions")
            .with(user(ACTOR)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(waiver))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
            .andReturn().getResponse().getContentAsString());
        UUID exceptionId = UUID.fromString(exception.path("id").asText());
        assertThatThrownBy(() -> jdbc.sql("UPDATE governance.policy_exceptions SET reason='Changed reason' WHERE id=:id")
            .param("id", exceptionId).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE governance.gate_evaluations SET blocked_count=0 WHERE id=:id")
            .param("id", gateId).update()).isInstanceOf(Exception.class);
        mvc.perform(post(base + "/gate-evaluations").with(user(ACTOR)).with(csrf())
            .header("Idempotency-Key", "gate-two").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("PASS"))
            .andExpect(jsonPath("$.ciExitCode").value(0))
            .andExpect(jsonPath("$.matchedExceptionVersionIds[0]").value(exceptionId.toString()));
        mvc.perform(get(base + "/gate-evaluations/" + gateId).with(user(ACTOR)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("FAIL"));
        mvc.perform(post(base + "/policy-exceptions/" + exceptionId + "/revoke")
            .with(user(ACTOR)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Exception no longer needed\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
        mvc.perform(post(base + "/gate-evaluations").with(user(ACTOR)).with(csrf())
            .header("Idempotency-Key", "gate-three").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("FAIL"));
        assertThat(jdbc.sql("SELECT count(*) FROM governance.gate_evaluations WHERE project_id=:project")
            .param("project", fixture.project()).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM governance.gate_evaluations WHERE project_id=:project AND sealed")
            .param("project", fixture.project()).query(Long.class).single()).isEqualTo(3);
    }

    @Test void missingBaselineReturnsPersistedConfigurationErrorAndCrossProjectWritesAreDenied() throws Exception {
        Fixture own = fixture(true);
        Fixture other = fixture(false);
        UUID job = job(own, emptyReport(own.identity()));
        String ownBase = path(own);
        mvc.perform(post(ownBase + "/gate-evaluations").with(user(ACTOR)).with(csrf())
            .header("Idempotency-Key", "no-baseline").contentType(MediaType.APPLICATION_JSON)
            .content(compare(own, job))).andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("ERROR"))
            .andExpect(jsonPath("$.ciExitCode").value(64))
            .andExpect(jsonPath("$.errorKind").value("CONFIGURATION"));
        UUID unfinished = queuedJob(own);
        mvc.perform(post(ownBase + "/gate-evaluations").with(user(ACTOR)).with(csrf())
            .header("Idempotency-Key", "unfinished-scan").contentType(MediaType.APPLICATION_JSON)
            .content(compare(own, unfinished))).andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("ERROR"))
            .andExpect(jsonPath("$.ciExitCode").value(70))
            .andExpect(jsonPath("$.errorKind").value("EXECUTION_OR_CONTRACT"));
        mvc.perform(post(ownBase + "/gate-evaluations").with(user(ACTOR)).with(csrf())
            .header("Idempotency-Key", "no-baseline").contentType(MediaType.APPLICATION_JSON)
            .content(compare(own, unfinished))).andExpect(status().isConflict());
        mvc.perform(post(path(other) + "/gate-evaluations").with(user(ACTOR)).with(csrf())
            .header("Idempotency-Key", "denied").contentType(MediaType.APPLICATION_JSON)
            .content(compare(other, job))).andExpect(status().isNotFound());
        mvc.perform(post(path(other) + "/policy-exceptions").with(user(ACTOR)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"targetBranch\":\"main\",\"ruleSetVersionId\":\""
                + other.rules() + "\",\"scopeType\":\"RULE\",\"scopeValue\":\"rule.a\","
                + "\"reason\":\"Reviewed temporary exception\",\"effectiveAt\":\""
                + Instant.now().minusSeconds(5) + "\",\"expiresAt\":\""
                + Instant.now().plusSeconds(3600) + "\"}"))
            .andExpect(status().isNotFound());
    }

    private Fixture fixture(boolean member) {
        UUID project = UUID.randomUUID(), repository = UUID.randomUUID(), ruleSet = UUID.randomUUID(), rules = UUID.randomUUID();
        String identity = "test:" + project;
        jdbc.sql("INSERT INTO project.projects(id,project_key,name,created_at,created_by) VALUES (:id,:key,'Governance',NOW(),:actor)")
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
            VALUES (:id,:project,:repository,'governance','Governance',:actor,NOW())
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
    private UUID job(Fixture fixture, String report) throws Exception {
        UUID id = UUID.randomUUID();
        byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        jdbc.sql("""
            INSERT INTO scanjob.scan_jobs(id,project_id,repository_id,rule_set_version_id,idempotency_key,
              request_sha256,status,outcome,created_by,created_at,report_bytes,report_sha256,
              scanner_version,result_schema_version)
            VALUES (:id,:project,:repository,:rules,:key,:digest,'SUCCEEDED','PASS',:actor,NOW(),
              :report,:digest,'0.2.1','0.1.0')
            """).param("id", id).param("project", fixture.project()).param("repository", fixture.repository())
            .param("rules", fixture.rules()).param("key", UUID.randomUUID().toString())
            .param("digest", digest).param("actor", UUID.fromString(ACTOR)).param("report", bytes).update();
        return id;
    }
    private UUID queuedJob(Fixture fixture) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO scanjob.scan_jobs(id,project_id,repository_id,rule_set_version_id,idempotency_key,
              request_sha256,status,created_by,created_at)
            VALUES (:id,:project,:repository,:rules,:key,:sha,'QUEUED',:actor,NOW())
            """).param("id", id).param("project", fixture.project())
            .param("repository", fixture.repository()).param("rules", fixture.rules())
            .param("key", UUID.randomUUID().toString()).param("sha", "a".repeat(64))
            .param("actor", UUID.fromString(ACTOR)).update();
        return id;
    }
    private static String emptyReport(String identity) {
        return "{\"schemaVersion\":\"0.1.0\",\"project\":{\"identity\":\"" + identity
            + "\"},\"artifacts\":[],\"components\":[],\"dependencies\":[],\"evidences\":[],\"findings\":[]}";
    }
    private static String violationReport(String identity) {
        return "{\"schemaVersion\":\"0.1.0\",\"project\":{\"identity\":\"" + identity
            + "\"},\"artifacts\":[{\"id\":\"artifact_a\",\"kind\":\"module\",\"language\":\"java\",\"repositoryPath\":\".\",\"qualifiedName\":\"test:module\"}],"
            + "\"components\":[],\"dependencies\":[],\"evidences\":[],\"findings\":[{\"id\":\"finding_a\",\"rule\":{\"id\":\"archguard.complexity-threshold\",\"version\":\"0.1.0\"},"
            + "\"subjectId\":\"artifact_a\",\"severity\":\"high\",\"extensions\":{\"archguard.metric\":[\"complexity.cyclomatic\"]}}]}";
    }
    private static String path(Fixture fixture) {
        return "/api/v1/projects/" + fixture.project() + "/repositories/" + fixture.repository();
    }
    private static String promote(Fixture fixture, UUID job, String commit) {
        return "{\"targetBranch\":\"main\",\"ruleSetVersionId\":\"" + fixture.rules()
            + "\",\"scanJobId\":\"" + job + "\",\"commitSha\":\"" + commit + "\"}";
    }
    private static String scope(Fixture fixture) {
        return "{\"targetBranch\":\"main\",\"ruleSetVersionId\":\"" + fixture.rules() + "\"}";
    }
    private static String compare(Fixture fixture, UUID job) {
        return "{\"targetBranch\":\"main\",\"ruleSetVersionId\":\"" + fixture.rules()
            + "\",\"candidateJobId\":\"" + job + "\"}";
    }
    private record Fixture(UUID project, UUID repository, UUID rules, String identity) {
        Fixture withRules(UUID newRules) { return new Fixture(project, repository, newRules, identity); }
    }
}
