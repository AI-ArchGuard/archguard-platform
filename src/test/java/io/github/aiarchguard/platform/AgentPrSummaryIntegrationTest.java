package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"archguard.agent.enabled=true"})
@AutoConfigureMockMvc
@Import(AgentPrSummaryIntegrationTest.FakeConfiguration.class)
class AgentPrSummaryIntegrationTest extends PostgresIntegrationTestSupport {
    private static final String ACTOR = "11111111-1111-1111-1111-111111111111";
    private static final String REPORT_SHA = "a".repeat(64);
    private static final String HEAD_SHA = "b".repeat(40);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired SummaryFakeModel model;

    @BeforeEach void reset() { model.calls.set(0); model.mode.set("SUPPORTED"); }

    @Test void summarizesOnlySelectedFindingsWithVerifiedPerFindingCitationsAndIdempotency() throws Exception {
        Fixture fixture = fixture();
        JsonNode created = create(fixture, "summary-one", fixture.selected(), fixture.revision());
        JsonNode result = awaitTerminal(fixture.project(), created.path("id").asText());
        assertThat(result.path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("purpose").asText()).isEqualTo("PR_SUMMARY");
        assertThat(result.at("/bindings/prHeadRevisionId").asText()).isEqualTo(fixture.revision().toString());
        assertThat(result.at("/bindings/findingIds").size()).isEqualTo(2);
        assertThat(result.at("/result/evidenceCoverage").asText()).isEqualTo("COMPLETE");
        assertThat(result.at("/result/citations").size()).isEqualTo(2);
        assertThat(result.toString()).doesNotContain(fixture.unselected().toString());
        JsonNode replay = create(fixture, "summary-one", fixture.selected(), fixture.revision());
        assertThat(replay.path("id").asText()).isEqualTo(created.path("id").asText());
        assertThat(model.calls.get()).isOne();
        assertThat(jdbc.sql("SELECT version FROM finding.findings WHERE id=:id")
            .param("id", fixture.selected().getFirst()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT outcome FROM scanjob.scan_jobs WHERE id=:id")
            .param("id", fixture.job()).query(String.class).single()).isEqualTo("PASS");
    }

    @Test void rejectsEmptyDuplicateForeignAndUnverifiedPrSelectionsBeforeCallingModel() throws Exception {
        Fixture fixture = fixture();
        Fixture foreign = fixture();
        reject(fixture, "empty", List.of(), fixture.revision());
        reject(fixture, "duplicate", List.of(fixture.selected().getFirst(), fixture.selected().getFirst()), fixture.revision());
        reject(fixture, "foreign", List.of(foreign.selected().getFirst()), fixture.revision());
        reject(fixture, "wrong-revision", fixture.selected(), foreign.revision());
        reject(fixture, "missing-revision", fixture.selected(), UUID.randomUUID());
        assertThat(model.calls.get()).isZero();
    }

    @Test void rejectsCitationTakenFromAnotherSelectedFinding() throws Exception {
        Fixture fixture = fixture();
        model.mode.set("CROSS_FINDING_CITATION");
        JsonNode created = create(fixture, "bad-citation", fixture.selected(), fixture.revision());
        JsonNode result = awaitTerminal(fixture.project(), created.path("id").asText());
        assertThat(result.path("state").asText()).isEqualTo("FAILED");
        assertThat(result.at("/failure/code").asText()).isEqualTo("CITATION_INVALID");
        assertThat(result.path("result").isNull()).isTrue();
        assertThat(model.calls.get()).isOne();
    }

    @Test void selectedFindingOrderIsCanonicalForIdempotency() throws Exception {
        Fixture fixture = fixture();
        JsonNode first = create(fixture, "same-set", fixture.selected(), fixture.revision());
        JsonNode reversed = create(fixture, "same-set", fixture.selected().reversed(), fixture.revision());
        assertThat(reversed.path("id").asText()).isEqualTo(first.path("id").asText());
        // Do not leave an asynchronous call running when the next test resets the shared fake counter.
        assertThat(awaitTerminal(fixture.project(), first.path("id").asText()).path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(model.calls.get()).isOne();
    }

    private JsonNode create(Fixture fixture, String key, List<UUID> selected, UUID revision) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/projects/{project}/agent/requests", fixture.project())
            .with(user(ACTOR)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
            .content(body(fixture, selected, revision))).andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString());
    }

    private void reject(Fixture fixture, String key, List<UUID> selected, UUID revision) throws Exception {
        mvc.perform(post("/api/v1/projects/{project}/agent/requests", fixture.project())
            .with(user(ACTOR)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
            .content(body(fixture, selected, revision))).andExpect(status().is4xxClientError());
    }

    private String body(Fixture fixture, List<UUID> selected, UUID revision) throws Exception {
        return mapper.writeValueAsString(Map.of("purpose", "PR_SUMMARY", "scanJobId", fixture.job(),
            "reportSha256", REPORT_SHA, "prHeadRevisionId", revision,
            "findingIds", selected, "documentVersionIds", List.of()));
    }

    private JsonNode awaitTerminal(UUID project, String id) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            JsonNode view = mapper.readTree(mvc.perform(get("/api/v1/projects/{project}/agent/requests/{id}",
                project, id).with(user(ACTOR))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
            if ("SUCCEEDED".equals(view.path("state").asText()) || "FAILED".equals(view.path("state").asText())) return view;
            Thread.sleep(25);
        }
        throw new AssertionError("Summary did not reach a terminal state");
    }

    private Fixture fixture() {
        var base = AgentExplanationIntegrationTest.fixture(jdbc);
        UUID otherFinding = UUID.randomUUID(), otherEvidence = UUID.randomUUID(),
            unselected = UUID.randomUUID(), revision = UUID.randomUUID(), gate = UUID.randomUUID();
        UUID repository = jdbc.sql("SELECT repository_id FROM scanjob.scan_jobs WHERE id=:job")
            .param("job", base.job()).query(UUID.class).single();
        UUID rules = jdbc.sql("SELECT rule_set_version_id FROM scanjob.scan_jobs WHERE id=:job")
            .param("job", base.job()).query(UUID.class).single();
        jdbc.sql("""
            INSERT INTO finding.evidences(id,project_id,job_id,scanner_evidence_id,kind,summary,path,
                start_line,start_column,end_line,end_column)
            VALUES (:id,:project,:job,'e2','DEPENDENCY','Other synthetic dependency','src/Other.java',2,1,2,10)
            """).param("id", otherEvidence).param("project", base.project()).param("job", base.job()).update();
        for (UUID id : List.of(otherFinding, unselected)) {
            jdbc.sql("""
                INSERT INTO finding.findings(id,project_id,job_id,scanner_finding_id,fingerprint,rule_id,
                    rule_version,severity,subject_id,message)
                VALUES (:id,:project,:job,:scanner,:fingerprint,'archguard.internal-dependency','0.1.0',
                    'high','module-b','Another dependency crosses the internal boundary.')
                """).param("id", id).param("project", base.project()).param("job", base.job())
                .param("scanner", "f" + id).param("fingerprint", id.toString().replace("-", "") + "0".repeat(32))
                .update();
        }
        jdbc.sql("INSERT INTO finding.finding_evidences(finding_id,evidence_id) VALUES (:finding,:evidence)")
            .param("finding", otherFinding).param("evidence", otherEvidence).update();
        jdbc.sql("""
            INSERT INTO governance.github_webhook_deliveries(delivery_id,payload_sha256,event_type,action,
                provider_repository_id,project_id,repository_id,event_at,processed_at,disposition,sealed)
            VALUES (:id,:sha,'pull_request','synchronize','123',:project,:repository,NOW(),NOW(),'APPLIED',true)
            """).param("id", revision).param("sha", "1".repeat(64))
            .param("project", base.project()).param("repository", repository).update();
        jdbc.sql("""
            INSERT INTO governance.github_pr_head_revisions(delivery_id,project_id,repository_id,
                external_id,head_sha,target_branch,event_at)
            VALUES (:id,:project,:repository,'27',:head,'main',NOW())
            """).param("id", revision).param("project", base.project())
            .param("repository", repository).param("head", HEAD_SHA).update();
        jdbc.sql("""
            INSERT INTO governance.gate_evaluations(id,project_id,repository_id,target_branch,rule_set_version_id,
                candidate_job_id,idempotency_key,request_sha256,outcome,ci_exit_code,policy_version,
                fingerprint_version,new_count,existing_count,resolved_count,blocked_count,evaluated_at,created_by)
            VALUES (:id,:project,:repository,'main',:rules,:job,:key,:sha,'PASS',0,'0.4.0','0.1.0',
                0,0,0,0,NOW(),:actor)
            """).param("id", gate).param("project", base.project()).param("repository", repository)
            .param("rules", rules).param("job", base.job()).param("key", UUID.randomUUID().toString())
            .param("sha", "2".repeat(64)).param("actor", UUID.fromString(ACTOR)).update();
        jdbc.sql("""
            INSERT INTO governance.report_submissions(id,project_id,repository_id,rule_set_version_id,
                provider,provider_repository_id,commit_sha,target_branch,pull_request_external_id,
                pull_request_head_sha,pull_request_base_sha,scanner_version,schema_version,report_sha256,
                request_digest,idempotency_key,scan_job_id,status,gate_evaluation_id,created_by,created_at,completed_at)
            VALUES (:id,:project,:repository,:rules,'github','123',:head,'main','27',:head,:base,
                '0.2.1','0.1.0',:sha,:digest,:key,:job,'COMPLETED',:gate,:actor,NOW(),NOW())
            """).param("id", UUID.randomUUID()).param("project", base.project()).param("repository", repository)
            .param("rules", rules).param("head", HEAD_SHA).param("base", "c".repeat(40))
            .param("sha", REPORT_SHA).param("digest", "3".repeat(64)).param("key", UUID.randomUUID().toString())
            .param("job", base.job()).param("gate", gate).param("actor", UUID.fromString(ACTOR)).update();
        return new Fixture(base.project(), base.job(), List.of(base.finding(), otherFinding), unselected, revision);
    }

    record Fixture(UUID project, UUID job, List<UUID> selected, UUID unselected, UUID revision) {}

    @TestConfiguration
    static class FakeConfiguration {
        @Bean @Primary SummaryFakeModel agentSummaryFakeModel() { return new SummaryFakeModel(); }
    }

    static class SummaryFakeModel implements AgentModelPort {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> mode = new AtomicReference<>("SUPPORTED");
        @Override public boolean syntheticOnly() { return true; }
        @Override public boolean available() { return true; }
        @Override public ModelResponse explain(ModelInput input) throws Exception {
            calls.incrementAndGet();
            JsonNode findings = new ObjectMapper().valueToTree(input).path("findings");
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode output = mapper.createObjectNode();
            output.put("schemaVersion", "0.1.0"); output.put("purpose", "PR_SUMMARY");
            ArrayNode ids = mapper.createArrayNode(), claims = mapper.createArrayNode(), rules = mapper.createArrayNode();
            ArrayNode suggestions = mapper.createArrayNode();
            for (JsonNode finding : findings) {
                String ref = finding.path("findingRef").asText();
                String citation = finding.path("evidence").path(0).path("citationId").asText();
                ids.add(citation);
                claims.addObject().put("findingRef", ref).put("text", "Selected Finding needs review.")
                    .putArray("citationIds").add(citation);
                rules.addObject().put("findingRef", ref).put("text", "The selected rule applies.")
                    .putArray("citationIds").add(citation);
                ObjectNode suggestion = suggestions.addObject();
                suggestion.put("kind", "HUMAN_VERIFICATION").put("text", "Verify the selected dependency.")
                    .put("requiresHumanReview", true);
                suggestion.putArray("findingRefs").add(ref);
                suggestion.putArray("citationIds").add(citation);
            }
            if ("CROSS_FINDING_CITATION".equals(mode.get())) {
                ((ArrayNode) claims.get(0).path("citationIds")).removeAll().add(ids.get(1).asText());
            }
            ObjectNode conclusion = output.putObject("conclusion");
            conclusion.put("kind", "SUPPORTED").put("text", "Review the selected PR Findings.");
            conclusion.set("citationIds", ids);
            output.set("claims", claims); output.set("ruleBasis", rules);
            output.set("suggestions", suggestions); output.putArray("limitations");
            return new ModelResponse(output.toString(), 100, 80, 1, "synthetic", "fake-v1");
        }
    }
}
