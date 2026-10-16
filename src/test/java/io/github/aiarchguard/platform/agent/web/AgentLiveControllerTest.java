package io.github.aiarchguard.platform.agent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.identity.CurrentActor;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentLiveControllerTest {
    private static final UUID PROJECT = UUID.randomUUID(), BATCH = UUID.randomUUID(), TEMPLATE = UUID.randomUUID(), OWNER = UUID.randomUUID();
    private static final String ORIGIN = "http://localhost:8081", TRACE = "c".repeat(32);
    private final FakeOperations operations = new FakeOperations();
    private final List<AuditEvent> audits = new ArrayList<>();
    private final AtomicBoolean failAudit = new AtomicBoolean();
    private AgentLiveController controller;
    private MockMvc mvc;

    @BeforeEach void prepare() {
        AuditRecorder audit = event -> { if (failAudit.get()) throw new IllegalStateException("private audit detail"); audits.add(event); };
        controller = new AgentLiveController(operations, new ObjectMapper().findAndRegisterModules(), audit,
            () -> new CurrentActor(OWNER, Set.of()), () -> TRACE);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new LiveRunExceptionHandler(() -> TRACE))
            .addFilters(new EnablementNoStoreFilter()).build();
    }

    @Test void acceptsOnlyTheTwoSelectionIdentifiersAndReturnsTheExistingReadLocation() throws Exception {
        mvc.perform(post(route()).header("Origin", ORIGIN).header("Idempotency-Key", "synthetic-live-key")
            .contentType(MediaType.APPLICATION_JSON).content(valid()))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Pragma", "no-cache"))
            .andExpect(header().string("Location", "/api/v1/projects/" + PROJECT + "/agent/requests/" + operations.view.id()))
            .andExpect(jsonPath("$.id").value(operations.view.id().toString()));
        assertThat(operations.runCalls).hasValue(1); assertThat(operations.lastInput.get()).isEqualTo(new LiveAgentRunInput(BATCH, TEMPLATE));
        assertThat(operations.lastKey.get()).isEqualTo("synthetic-live-key"); assertThat(audits).isEmpty();
    }

    @Test void acceptsExactly8192Utf8BytesWithoutRaisingTheBound() throws Exception {
        String body = valid() + " ".repeat(8192 - valid().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        mvc.perform(post(route()).header("Origin", ORIGIN).header("Idempotency-Key", "synthetic-exact-body-bound")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
        assertThat(operations.runCalls).hasValue(1);
    }

    @Test void authorizationPrecedesReadingAnyBodyBytesAndInvalidKeyValidation() throws Exception {
        var reads = new AtomicInteger();
        var request = new MockHttpServletRequest() {
            @Override public ServletInputStream getInputStream() throws IllegalStateException { reads.incrementAndGet(); throw new IllegalStateException("body must not be read"); }
        };
        request.addHeader("Origin", ORIGIN); operations.authorizationFailure = new ProjectPermissionDeniedException();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.create(PROJECT, request))
            .isInstanceOf(ProjectPermissionDeniedException.class).hasNoCause();
        assertThat(reads).hasValue(0); assertThat(operations.runCalls).hasValue(0);
        assertThat(audits).hasSize(1); assertThat(audits.getFirst().action()).isEqualTo("agent.live.http_rejected");
    }

    @Test void rejectsMalformedDuplicateUnknownMissingNullAndWrongTypeFieldsWithoutRunning() throws Exception {
        for (String body : List.of("", "{", "null", "{}", "[]", valid() + " {}", valid().replace("\"batchId\"", "\"unknown\""),
                "{\"batchId\":\"" + BATCH + "\"}",
                "{\"batchId\":null,\"templateRequestId\":\"" + TEMPLATE + "\"}",
                "{\"batchId\":\"" + BATCH + "\",\"templateRequestId\":null}",
                "{\"batchId\":123,\"templateRequestId\":\"" + TEMPLATE + "\"}",
                "{\"batchId\":\"" + BATCH + "\",\"batchId\":\"" + BATCH + "\",\"templateRequestId\":\"" + TEMPLATE + "\"}",
                "{\"batchId\":\"" + BATCH + "\",\"templateRequestId\":true}",
                "{\"batchId\":\"" + BATCH + "\",\"templateRequestId\":\"" + TEMPLATE + "\",\"apiKey\":\"synthetic-sensitive-input\"}")) {
            mvc.perform(post(route()).header("Origin", ORIGIN).header("Idempotency-Key", "synthetic-live-key")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value("agent.invalid"));
        }
        assertThat(operations.runCalls).hasValue(0); assertSafeAudit();
    }

    @Test void readsNoMoreThan8193BytesAndRejectsTheOversizedBody() throws Exception {
        var reads = new AtomicInteger(); byte[] body = " ".repeat(20_000).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = new MockHttpServletRequest() {
            @Override public ServletInputStream getInputStream() {
                return new org.springframework.mock.web.DelegatingServletInputStream(new java.io.ByteArrayInputStream(body) {
                    @Override public synchronized int read(byte[] bytes, int offset, int length) {
                        int count = super.read(bytes, offset, length); if (count > 0) reads.addAndGet(count); return count;
                    }
                    @Override public synchronized int read() { int value = super.read(); if (value >= 0) reads.incrementAndGet(); return value; }
                });
            }
        };
        request.addHeader("Origin", ORIGIN); request.addHeader("Idempotency-Key", "synthetic-live-key");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.create(PROJECT, request)).isInstanceOf(AgentInvalidException.class);
        assertThat(reads.get()).isBetween(1, 8193); assertThat(operations.runCalls).hasValue(0); assertSafeAudit();
    }

    @Test void rejectsShortenedAndBase64UuidsRatherThanTreatingThemAsCanonicalSelectionIds() throws Exception {
        for (String id : List.of("1-1-1-1-1", "AAAAAAAAAAAAAAAAAAAAAA==", "not-a-uuid", " " + BATCH)) {
            mvc.perform(post(route()).header("Origin", ORIGIN).header("Idempotency-Key", "synthetic-canonical-id")
                .contentType(MediaType.APPLICATION_JSON).content(valid().replace(BATCH.toString(), id)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("agent.invalid"));
        }
        assertThat(operations.runCalls).hasValue(0); assertSafeAudit();
    }

    @Test void missingEmptyControlOversizeAndDuplicateIdempotencyHeadersCannotRun() throws Exception {
        for (String key : List.of("", "contains space", "a".repeat(129), "line\nfeed")) {
            mvc.perform(post(route()).header("Origin", ORIGIN).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(valid())).andExpect(status().isBadRequest());
        }
        mvc.perform(post(route()).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(valid())).andExpect(status().isBadRequest());
        mvc.perform(post(route()).header("Origin", ORIGIN).header("Idempotency-Key", "one", "two")
            .contentType(MediaType.APPLICATION_JSON).content(valid())).andExpect(status().isBadRequest());
        assertThat(operations.runCalls).hasValue(0); assertSafeAudit();
    }

    @Test void authorizationAndUnexpectedFailuresUseStableSanitizedCategories() throws Exception {
        operations.authorizationFailure = new ProjectPermissionDeniedException();
        mvc.perform(post(route()).header("Origin", ORIGIN).header("Idempotency-Key", "synthetic-live-key")
            .contentType(MediaType.APPLICATION_JSON).content(valid())).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("agent.forbidden"));
        operations.authorizationFailure = null; operations.runFailure = new IllegalStateException("synthetic-sensitive-input", new IOException("private path"));
        String response = mvc.perform(post(route()).header("Origin", ORIGIN).header("Idempotency-Key", "synthetic-live-key")
            .contentType(MediaType.APPLICATION_JSON).content(valid())).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("agent.unavailable")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("synthetic-sensitive-input", "private path", "IllegalStateException"); assertSafeAudit();
    }

    @Test void failedBoundaryAuditIsAnExplicitUnavailableResponseNeverAnUnauditedRun() throws Exception {
        operations.authorizationFailure = new ProjectPermissionDeniedException(); failAudit.set(true);
        mvc.perform(post(route()).header("Origin", ORIGIN).header("Idempotency-Key", "synthetic-live-key")
            .contentType(MediaType.APPLICATION_JSON).content(valid())).andExpect(status().isServiceUnavailable());
        assertThat(operations.runCalls).hasValue(0);
    }

    @Test void applicationConflictsMissingStateAndQuotaHaveStableCategoriesWithoutDuplicateBoundaryAudit() throws Exception {
        RuntimeException[] failures = {new AgentConflictException(), new AgentEnablementInvalidStateException(),
            new AgentNotFoundException(), new AgentQuotaExceededException()};
        int[] statuses = {409, 409, 404, 429};
        String[] codes = {"agent.conflict", "agent.enablement_invalid", "agent.not_found", "agent.quota_exceeded"};
        for (int index = 0; index < failures.length; index++) {
            operations.runFailure = failures[index];
            mvc.perform(post(route()).header("Origin", ORIGIN).header("Idempotency-Key", "synthetic-application-failure")
                .contentType(MediaType.APPLICATION_JSON).content(valid())).andExpect(status().is(statuses[index]))
                .andExpect(jsonPath("$.code").value(codes[index])).andExpect(header().string("Cache-Control", "no-store"));
        }
        assertThat(audits).as("the application, not this boundary, owns and audits run failures").isEmpty();
    }

    @Test void noStoreFilterRunsBeforeAnUnauthorizedResponseIncludingContextPath() throws Exception {
        var request = new MockHttpServletRequest("POST", "/local" + route()); request.setContextPath("/local");
        var response = new MockHttpServletResponse();
        new EnablementNoStoreFilter().doFilter(request, response, (incoming, outgoing) -> ((jakarta.servlet.http.HttpServletResponse) outgoing).sendError(401));
        assertThat(response.getStatus()).isEqualTo(401); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
    }

    private void assertSafeAudit() {
        assertThat(audits).allSatisfy(event -> {
            assertThat(event.actorId()).isEqualTo(OWNER); assertThat(event.projectId()).isEqualTo(PROJECT);
            assertThat(event.traceId()).isEqualTo(TRACE); assertThat(event.metadata()).containsOnlyKeys("reason", "status");
        });
        assertThat(audits.toString()).doesNotContain("synthetic-sensitive-input", BATCH.toString(), TEMPLATE.toString(), "synthetic-live-key");
    }
    private String valid() { return "{\"batchId\":\"" + BATCH + "\",\"templateRequestId\":\"" + TEMPLATE + "\"}"; }
    private String route() { return "/api/v1/projects/" + PROJECT + "/agent/live-requests"; }

    private static final class FakeOperations implements AgentLiveRunOperations {
        final AtomicInteger runCalls = new AtomicInteger();
        final AtomicReference<LiveAgentRunInput> lastInput = new AtomicReference<>();
        final AtomicReference<String> lastKey = new AtomicReference<>();
        RuntimeException authorizationFailure, runFailure;
        final AgentRequestView view = new AgentRequestView(UUID.randomUUID(), PROJECT, OWNER, TRACE, "FINDING_EXPLANATION", "QUEUED",
            null, null, null, null, Instant.now(), Instant.now());
        @Override public void authorize(UUID project, String origin, boolean secure) {
            assertThat(project).isEqualTo(PROJECT); assertThat(origin).isEqualTo(ORIGIN);
            if (authorizationFailure != null) throw authorizationFailure;
        }
        @Override public AgentRequestView run(UUID project, LiveAgentRunInput input, String key, String origin, boolean secure) {
            runCalls.incrementAndGet(); lastInput.set(input); lastKey.set(key);
            if (runFailure != null) throw runFailure;
            return view;
        }
    }
}
