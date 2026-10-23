package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.aiarchguard.platform.agent.internal.LiveResponsePort;
import io.github.aiarchguard.platform.common.TraceTaskScope;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class DeepSeekResponseDiagnosticsTest {
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String KEY = "synthetic-diagnostics-key";
    private static final String PRIVATE_BODY = "private-body-sentinel";
    private static final String PRIVATE_FIELD = "private-field-sentinel";
    private static final String PRIVATE_CONTENT_TYPE = "private-content-type-sentinel";
    private static final String PRIVATE_MODEL = "private-model-sentinel";
    private static final String PRIVATE_ID = "private-response-id-sentinel";
    private static final String PRIVATE_PARSER = "private-parser-sentinel";
    private static final String PRIVATE_WORKER = "private-worker-sentinel";
    private static final URI OFFICIAL_ENDPOINT = URI.create("https://api.deepseek.com/responses");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Hand-written synthetic wire data, independent of production encoding and codec test helpers.
    private static final String VALID_ENVELOPE = """
        {"id":"private-response-id-sentinel","object":"response","created_at":1,
         "status":"completed","model":"deepseek-flash","error":null,"incomplete_details":null,
         "previous_response_id":null,"store":false,"tools":[],"background":false,
         "output":[{"id":"msg-synthetic-1","type":"message","role":"assistant","status":"completed",
           "content":[{"type":"output_text","text":"{\\"schemaVersion\\":\\"0.1.0\\",\\"purpose\\":\\"FINDING_EXPLANATION\\",\\"conclusion\\":{\\"kind\\":\\"INSUFFICIENT\\",\\"text\\":\\"private-body-sentinel\\",\\"citationIds\\":[]},\\"claims\\":[],\\"ruleBasis\\":[],\\"suggestions\\":[],\\"limitations\\":[\\"Synthetic fixture only.\\"]}","annotations":[],"logprobs":[]}]}],
         "usage":{"input_tokens":100,"output_tokens":20,"total_tokens":120,
           "input_tokens_details":{"cached_tokens":40},"output_tokens_details":{"reasoning_tokens":0}}}
        """;

    private final Logger logger = (Logger) LoggerFactory.getLogger(DeepSeekLiveResponseClient.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) {
            // Logback otherwise resolves MDC lazily, after TraceTaskScope has restored worker state.
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private Map<String, String> originalMdc;
    private Level originalLevel;
    private boolean originalAdditive;

    @BeforeEach void captureClientDiagnostics() {
        originalMdc = MDC.getCopyOfContextMap();
        originalLevel = logger.getLevel();
        originalAdditive = logger.isAdditive();
        logger.setLevel(Level.TRACE);
        logger.setAdditive(false);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach void restoreLoggingContext() {
        logger.detachAppender(logs);
        logs.stop();
        logger.setLevel(originalLevel);
        logger.setAdditive(originalAdditive);
        MDC.clear();
        if (originalMdc != null) MDC.setContextMap(originalMdc);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedResponses")
    void classifiesRejectionsOnceWithoutExposingUntrustedResponseValues(RejectedResponse scenario) {
        AtomicInteger calls = new AtomicInteger();
        var client = new DeepSeekLiveResponseClient(MAPPER, (request, timeout) -> {
            calls.incrementAndGet();
            assertThat(request.uri()).isEqualTo(OFFICIAL_ENDPOINT);
            return new DeepSeekHttpTransport.Response(200, scenario.contentType(), scenario.body());
        });

        withIsolatedTrace(() -> {
            if (scenario.knownUsage()) {
                var reply = assertDoesNotThrow(() -> send(client));
                assertThat(reply.rawJson()).isNull();
                assertThat(reply.outputFailureCode()).isEqualTo("OUTPUT_INVALID");
                assertTrustedUsage(reply);
                assertThat(reply.toString()).doesNotContain(KEY, PRIVATE_BODY, PRIVATE_FIELD, PRIVATE_PARSER);
            } else {
                assertThatThrownBy(() -> send(client)).isInstanceOf(LiveResponsePort.CallFailure.class)
                    .hasMessage("OUTPUT_INVALID").hasNoCause()
                    .satisfies(failure -> assertThat(((LiveResponsePort.CallFailure) failure).code()).isEqualTo("OUTPUT_INVALID"));
            }
        });

        assertThat(calls).hasValue(1);
        if (scenario.wipedAfterClientReceivesBody()) assertThat(scenario.body()).containsOnly((byte) 0);
        assertDiagnostic(scenario.stage(), scenario.reason(), scenario.unknownFieldCount());
    }

    @Test void unclassifiedOutputInvalidFailureUsesFixedFallbackWithoutReadingExceptionDetails() {
        AtomicInteger calls = new AtomicInteger();
        var client = new DeepSeekLiveResponseClient(MAPPER, (request, timeout) -> {
            calls.incrementAndGet();
            throw new DeepSeekTransportFailure("OUTPUT_INVALID");
        });
        withIsolatedTrace(() -> assertThatThrownBy(() -> send(client))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("OUTPUT_INVALID").hasNoCause());
        assertThat(calls).hasValue(1);
        assertDiagnostic("RESPONSE_VALIDATION", "UNCLASSIFIED");
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 33, Integer.MAX_VALUE})
    void rejectsOutOfRangeDiagnosticCountsBeforeTheyCanReachTheLogger(int count) {
        assertThatThrownBy(() -> new DeepSeekResponseRejection(
                DeepSeekResponseRejection.Reason.ENVELOPE_UNKNOWN_FIELDS, count))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid DeepSeek diagnostic classification").hasNoCause();
    }

    @Test void unrelatedReasonsCannotCarryUnknownFieldCounts() {
        assertThatThrownBy(() -> new DeepSeekResponseRejection(DeepSeekResponseRejection.Reason.ACCOUNTING_FIELDS, 1))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid DeepSeek diagnostic classification").hasNoCause();
    }

    @Test void unknownFieldReasonCannotBeCreatedWithoutAnObservedCount() {
        assertThatThrownBy(() -> new DeepSeekResponseRejection(DeepSeekResponseRejection.Reason.ENVELOPE_UNKNOWN_FIELDS))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid DeepSeek diagnostic classification").hasNoCause();
    }

    @Test void consecutiveCallsDoNotCarryUnknownCountsIntoOtherFailuresOrSuccess() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(VALID_ENVELOPE);
        root.put(PRIVATE_FIELD + KEY, PRIVATE_BODY);
        byte[][] bodies = {MAPPER.writeValueAsBytes(root), "[]".getBytes(StandardCharsets.UTF_8),
            VALID_ENVELOPE.getBytes(StandardCharsets.UTF_8)};
        AtomicInteger calls = new AtomicInteger();
        var client = new DeepSeekLiveResponseClient(MAPPER, (request, timeout) ->
            new DeepSeekHttpTransport.Response(200, "application/json", bodies[calls.getAndIncrement()]));
        withIsolatedTrace(() -> assertThatThrownBy(() -> send(client))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("OUTPUT_INVALID").hasNoCause());
        assertDiagnostic("RESPONSE_ENVELOPE", "ENVELOPE_UNKNOWN_FIELDS", 1);
        logs.list.clear();
        withIsolatedTrace(() -> assertThatThrownBy(() -> send(client))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("OUTPUT_INVALID").hasNoCause());
        assertDiagnostic("RESPONSE_ENVELOPE", "ENVELOPE_NOT_OBJECT");
        logs.list.clear();
        withIsolatedTrace(() -> assertTrustedUsage(send(client)));
        assertThat(logs.list).isEmpty();
        assertThat(calls).hasValue(3);
        for (byte[] body : bodies) assertThat(body).containsOnly((byte) 0);
    }

    @Test void acceptedSyntheticResponseHasNoRejectionEventAndStillWipesReceivedBytes() throws Exception {
        byte[] bytes = VALID_ENVELOPE.getBytes(StandardCharsets.UTF_8);
        String expectedBusinessJson = MAPPER.readTree(VALID_ENVELOPE).at("/output/0/content/0/text").textValue();
        AtomicInteger calls = new AtomicInteger();
        var client = new DeepSeekLiveResponseClient(MAPPER, (request, timeout) -> {
            calls.incrementAndGet();
            assertThat(request.uri()).isEqualTo(OFFICIAL_ENDPOINT);
            return new DeepSeekHttpTransport.Response(200, "application/json; charset=utf-8", bytes);
        });
        withIsolatedTrace(() -> {
            var reply = send(client);
            assertThat(reply.rawJson()).isEqualTo(expectedBusinessJson);
            assertThat(reply.outputFailureCode()).isNull();
            assertTrustedUsage(reply);
        });
        assertThat(calls).hasValue(1);
        assertThat(bytes).containsOnly((byte) 0);
        assertThat(logs.list).isEmpty();
    }

    @Test void acceptedInertMetadataIsNotExposedInBusinessReplyOrDiagnostics() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(VALID_ENVELOPE);
        approvedMetadata(root);
        byte[] bytes = MAPPER.writeValueAsBytes(root);
        AtomicInteger calls = new AtomicInteger();
        var client = new DeepSeekLiveResponseClient(MAPPER, (request, timeout) -> {
            calls.incrementAndGet();
            assertThat(request.uri()).isEqualTo(OFFICIAL_ENDPOINT);
            return new DeepSeekHttpTransport.Response(200, "application/json", bytes);
        });
        withIsolatedTrace(() -> {
            var reply = assertDoesNotThrow(() -> send(client));
            assertThat(reply.rawJson()).isEqualTo(root.at("/output/0/content/0/text").textValue())
                .doesNotContain("user", "max_tool_calls", "top_logprobs", "completed_at", "created_at");
            assertThat(reply.outputFailureCode()).isNull();
            assertTrustedUsage(reply);
        });
        assertThat(calls).hasValue(1);
        assertThat(bytes).containsOnly((byte) 0);
        assertThat(logs.list).isEmpty();
    }

    @Test void chunkedBodyLimitRetainsClassificationAcrossAsyncCompletionAndLogsOnCallerTrace() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger httpRequests = new AtomicInteger();
        AtomicInteger exchanges = new AtomicInteger();
        byte[] oversized = (PRIVATE_BODY + "x".repeat(65537)).getBytes(StandardCharsets.UTF_8);
        server.createContext("/responses", exchange -> {
            httpRequests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) { output.write(oversized); }
            catch (java.io.IOException closedByClient) { /* The bounded client cancels the oversized body. */ }
        });
        server.start();
        var http = new BoundedJdkHttpExchange();
        try {
            var client = new DeepSeekLiveResponseClient(MAPPER, (request, timeout) -> {
                exchanges.incrementAndGet();
                // Only this test seam rewrites the fixed production target to a loopback fake.
                assertThat(request.uri()).isEqualTo(OFFICIAL_ENDPOINT);
                var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/responses"))
                    .timeout(timeout).POST(request.bodyPublisher().orElseThrow());
                request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
                return http.exchange(builder.build(), timeout);
            });
            withIsolatedTrace(() -> assertThatThrownBy(() -> client.send(new byte[] {'{', '}'}, KEY,
                    Duration.ofSeconds(2), Set.of("deepseek-flash")))
                .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("OUTPUT_INVALID").hasNoCause());
            assertThat(exchanges).hasValue(1);
            assertThat(httpRequests).hasValue(1);
            assertDiagnostic("HTTP_RESPONSE", "HTTP_BODY_LIMIT");
            http.close();
            assertThat(http.awaitTermination(Duration.ofSeconds(3))).isTrue();
        } finally {
            http.close();
            server.stop(0);
        }
    }

    @ParameterizedTest(name = "loopback HTTP: {0}")
    @MethodSource("envelopeShapeResponses")
    void distinguishesEnvelopeShapesAcrossLoopbackHttpWithoutRetryOrResponseDisclosure(RejectedResponse scenario) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger httpRequests = new AtomicInteger();
        AtomicInteger exchanges = new AtomicInteger();
        server.createContext("/responses", exchange -> {
            httpRequests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, scenario.body().length);
            try (var output = exchange.getResponseBody()) { output.write(scenario.body()); }
        });
        server.start();
        var http = new BoundedJdkHttpExchange();
        try {
            var client = new DeepSeekLiveResponseClient(MAPPER, (request, timeout) -> {
                exchanges.incrementAndGet();
                assertThat(request.uri()).isEqualTo(OFFICIAL_ENDPOINT);
                var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/responses"))
                    .timeout(timeout).POST(request.bodyPublisher().orElseThrow());
                request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
                return http.exchange(builder.build(), timeout);
            });
            withIsolatedTrace(() -> assertThatThrownBy(() -> client.send(new byte[] {'{', '}'}, KEY,
                    Duration.ofSeconds(2), Set.of("deepseek-flash")))
                .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("OUTPUT_INVALID").hasNoCause());
            assertThat(exchanges).hasValue(1);
            assertThat(httpRequests).hasValue(1);
            assertDiagnostic(scenario.stage(), scenario.reason(), scenario.unknownFieldCount());
            http.close();
            assertThat(http.awaitTermination(Duration.ofSeconds(3))).isTrue();
        } finally {
            http.close();
            server.stop(0);
        }
    }

    private static Stream<RejectedResponse> envelopeShapeResponses() throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(VALID_ENVELOPE);
        root.put(PRIVATE_FIELD + KEY + "\r\n🔑", PRIVATE_BODY);
        return Stream.of(
            rawCase("nonobject envelope", "RESPONSE_ENVELOPE", "ENVELOPE_NOT_OBJECT", "[]", false),
            unknownRootCase("unknown envelope field", root, 1));
    }

    private static Stream<RejectedResponse> rejectedResponses() throws Exception {
        List<RejectedResponse> cases = new ArrayList<>();
        cases.add(new RejectedResponse("wrong MIME", "HTTP_RESPONSE", "HTTP_CONTENT_TYPE",
            "text/" + PRIVATE_CONTENT_TYPE, VALID_ENVELOPE.getBytes(StandardCharsets.UTF_8), false, false));
        cases.add(new RejectedResponse("empty HTTP body", "HTTP_RESPONSE", "HTTP_BODY_EMPTY",
            "application/json", new byte[0], false, false));
        cases.add(new RejectedResponse("missing HTTP body", "HTTP_RESPONSE", "HTTP_BODY_EMPTY",
            "application/json", null, false, false));
        cases.add(new RejectedResponse("oversized HTTP body", "HTTP_RESPONSE", "HTTP_BODY_LIMIT",
            "application/json", (PRIVATE_BODY + "x".repeat(65537)).getBytes(StandardCharsets.UTF_8), false, false));
        cases.add(rawCase("malformed envelope JSON", "RESPONSE_JSON", "RESPONSE_JSON", PRIVATE_PARSER + " {", false));
        cases.add(rawCase("duplicate envelope JSON field", "RESPONSE_JSON", "RESPONSE_JSON",
            VALID_ENVELOPE.replace("\"created_at\":1", "\"created_at\":1,\"created_at\":2"), false));
        cases.add(rawCase("trailing envelope JSON", "RESPONSE_JSON", "RESPONSE_JSON",
            VALID_ENVELOPE + " {\"" + PRIVATE_PARSER + "\":true}", false));
        cases.add(rawCase("unknown field does not hide duplicate JSON", "RESPONSE_JSON", "RESPONSE_JSON",
            VALID_ENVELOPE.replace("\"created_at\":1", "\"created_at\":1,\"" + PRIVATE_FIELD
                + "\":true,\"" + PRIVATE_FIELD + "\":false"), false));
        cases.add(rawCase("unknown field does not hide trailing JSON", "RESPONSE_JSON", "RESPONSE_JSON",
            VALID_ENVELOPE.replace("\"created_at\":1", "\"created_at\":1,\"" + PRIVATE_FIELD + "\":true")
                + " {\"" + PRIVATE_PARSER + "\":true}", false));
        for (String raw : List.of("[]", "null", "true", "17", "\"" + PRIVATE_BODY + "\"")) {
            cases.add(rawCase("nonobject envelope " + cases.size(), "RESPONSE_ENVELOPE", "ENVELOPE_NOT_OBJECT", raw, false));
        }
        ObjectNode unknownRoot = (ObjectNode) MAPPER.readTree(VALID_ENVELOPE);
        unknownRoot.put(PRIVATE_FIELD + KEY + "\r\n🔑", PRIVATE_BODY);
        cases.add(unknownRootCase("unknown envelope field", unknownRoot, 1));
        ObjectNode unknownWithBadUsage = unknownRoot.deepCopy();
        usage(unknownWithBadUsage).put("input_tokens", PRIVATE_BODY);
        cases.add(unknownRootCase("unknown envelope field precedes usage", unknownWithBadUsage, 1));
        for (int[] boundary : new int[][] {{2, 2}, {31, 31}, {32, 32}, {33, 32}, {1000, 32}}) {
            ObjectNode root = (ObjectNode) MAPPER.readTree(VALID_ENVELOPE);
            for (int index = 0; index < boundary[0]; index++) root.put(PRIVATE_FIELD + index, PRIVATE_BODY);
            cases.add(unknownRootCase("unknown envelope fields " + boundary[0], root, boundary[1]));
        }
        envelope(cases, "missing object marker", "ENVELOPE_OBJECT", root -> root.remove("object"));
        envelope(cases, "wrong object marker", "ENVELOPE_OBJECT", root -> root.put("object", PRIVATE_BODY));
        envelope(cases, "missing status", "ENVELOPE_STATUS", root -> root.remove("status"));
        envelope(cases, "incomplete status", "ENVELOPE_STATUS", root -> root.put("status", "incomplete"));
        envelope(cases, "missing response ID", "ENVELOPE_IDENTIFIER", root -> root.remove("id"));
        envelope(cases, "blank response ID", "ENVELOPE_IDENTIFIER", root -> root.put("id", " "));
        envelope(cases, "invalid response ID pattern", "ENVELOPE_IDENTIFIER", root -> root.put("id", PRIVATE_ID + " /"));
        envelope(cases, "missing model", "ENVELOPE_MODEL", root -> root.remove("model"));
        envelope(cases, "blank model", "ENVELOPE_MODEL", root -> root.put("model", " "));
        envelope(cases, "unapproved model", "ENVELOPE_MODEL", root -> root.put("model", PRIVATE_MODEL));
        envelope(cases, "provider error", "ENVELOPE_ERROR", root -> root.putObject("error").put(PRIVATE_FIELD, PRIVATE_BODY));
        envelope(cases, "incomplete details", "ENVELOPE_ERROR", root -> root.putObject("incomplete_details").put(PRIVATE_FIELD, PRIVATE_BODY));
        envelope(cases, "previous response", "ENVELOPE_ERROR", root -> root.put("previous_response_id", PRIVATE_ID));
        envelope(cases, "enabled storage", "ENVELOPE_STORE", root -> root.put("store", true));
        envelope(cases, "nonboolean storage", "ENVELOPE_STORE", root -> root.put("store", PRIVATE_BODY));
        envelope(cases, "nonempty tools", "ENVELOPE_TOOLS", root -> root.putArray("tools").addObject().put(PRIVATE_FIELD, PRIVATE_BODY));
        envelope(cases, "nonarray tools", "ENVELOPE_TOOLS", root -> root.put("tools", PRIVATE_BODY));
        envelope(cases, "enabled background", "ENVELOPE_BACKGROUND", root -> root.put("background", true));
        envelope(cases, "nonboolean background", "ENVELOPE_BACKGROUND", root -> root.put("background", PRIVATE_BODY));
        for (String field : List.of("user", "max_tool_calls", "top_logprobs", "completed_at")) {
            envelope(cases, "private metadata value " + field, "ENVELOPE_METADATA", root -> root.put(field, PRIVATE_BODY));
        }
        envelope(cases, "out-of-order metadata", "ENVELOPE_METADATA", root -> {
            approvedMetadata(root); root.put("created_at", 3);
        });
        ObjectNode approvedWithUnknown = (ObjectNode) MAPPER.readTree(VALID_ENVELOPE);
        approvedMetadata(approvedWithUnknown);
        approvedWithUnknown.put(PRIVATE_FIELD, PRIVATE_BODY);
        cases.add(unknownRootCase("approved metadata does not hide unknown field", approvedWithUnknown, 1));
        envelope(cases, "empty object remains missing object marker", "ENVELOPE_OBJECT", ObjectNode::removeAll);
        envelope(cases, "approved metadata does not enable tools", "ENVELOPE_TOOLS", root -> {
            approvedMetadata(root); root.putArray("tools").addObject().put(PRIVATE_FIELD, PRIVATE_BODY);
        });
        accounting(cases, "approved metadata does not repair usage", "ACCOUNTING_TOKENS", root -> {
            approvedMetadata(root); usage(root).put("input_tokens", PRIVATE_BODY);
        });
        output(cases, "approved metadata preserves usage on bad business", "OUTPUT_JSON", root -> {
            approvedMetadata(root); outputText(root).put("text", PRIVATE_PARSER + " {");
        });
        output(cases, "zero top_logprobs does not enable output logprobs", "OUTPUT_STRUCTURE", root -> {
            approvedMetadata(root); outputText(root).putArray("logprobs").add(PRIVATE_BODY);
        });
        for (String id : List.of("resp-" + KEY, "sk-" + PRIVATE_ID, "SK-" + PRIVATE_ID,
                "ghp_" + PRIVATE_ID, "github_pat_" + PRIVATE_ID)) {
            envelope(cases, "sensitive provider identifier " + cases.size(), "PROVIDER_IDENTIFIER_SENSITIVE", root -> root.put("id", id));
        }

        accounting(cases, "missing usage", "ACCOUNTING_FIELDS", root -> root.remove("usage"));
        accounting(cases, "nonobject usage", "ACCOUNTING_FIELDS", root -> root.put("usage", PRIVATE_BODY));
        accounting(cases, "unknown usage field", "ACCOUNTING_FIELDS", root -> usage(root).put(PRIVATE_FIELD, PRIVATE_BODY));
        for (String detail : List.of("input_tokens_details", "output_tokens_details")) {
            accounting(cases, "missing " + detail, "ACCOUNTING_FIELDS", root -> usage(root).remove(detail));
            accounting(cases, "nonobject " + detail, "ACCOUNTING_FIELDS", root -> usage(root).put(detail, PRIVATE_BODY));
            accounting(cases, "unknown " + detail + " field", "ACCOUNTING_FIELDS", root -> ((ObjectNode) usage(root).get(detail)).put(PRIVATE_FIELD, PRIVATE_BODY));
        }
        accounting(cases, "missing input token count", "ACCOUNTING_TOKENS", root -> usage(root).remove("input_tokens"));
        accounting(cases, "negative input tokens", "ACCOUNTING_TOKENS", root -> usage(root).put("input_tokens", -1));
        accounting(cases, "fractional output tokens", "ACCOUNTING_TOKENS", root -> usage(root).put("output_tokens", 1.5));
        accounting(cases, "over-limit output tokens", "ACCOUNTING_TOKENS", root -> usage(root).put("output_tokens", 1501));
        accounting(cases, "negative total tokens", "ACCOUNTING_TOKENS", root -> usage(root).put("total_tokens", -1));
        accounting(cases, "inconsistent total tokens", "ACCOUNTING_TOKENS", root -> usage(root).put("total_tokens", 121));
        accounting(cases, "missing cached token count", "ACCOUNTING_CACHE", root -> inputDetails(root).remove("cached_tokens"));
        accounting(cases, "negative cached tokens", "ACCOUNTING_CACHE", root -> inputDetails(root).put("cached_tokens", -1));
        accounting(cases, "fractional cached tokens", "ACCOUNTING_CACHE", root -> inputDetails(root).put("cached_tokens", 0.5));
        accounting(cases, "cache exceeds input", "ACCOUNTING_CACHE", root -> inputDetails(root).put("cached_tokens", 101));
        accounting(cases, "missing reasoning tokens", "ACCOUNTING_REASONING", root -> outputDetails(root).remove("reasoning_tokens"));
        accounting(cases, "noninteger reasoning tokens", "ACCOUNTING_REASONING", root -> outputDetails(root).put("reasoning_tokens", PRIVATE_BODY));
        accounting(cases, "negative reasoning tokens", "ACCOUNTING_REASONING", root -> outputDetails(root).put("reasoning_tokens", -1));
        accounting(cases, "nonzero reasoning tokens", "ACCOUNTING_REASONING", root -> outputDetails(root).put("reasoning_tokens", 1));

        output(cases, "missing output", "OUTPUT_STRUCTURE", root -> root.remove("output"));
        output(cases, "nonarray output", "OUTPUT_STRUCTURE", root -> root.put("output", PRIVATE_BODY));
        output(cases, "multiple output messages", "OUTPUT_STRUCTURE", root -> root.withArray("output").add(root.at("/output/0").deepCopy()));
        output(cases, "unknown message field", "OUTPUT_STRUCTURE", root -> message(root).put(PRIVATE_FIELD, PRIVATE_BODY));
        output(cases, "tool message", "OUTPUT_STRUCTURE", root -> message(root).put("type", "function_call"));
        output(cases, "missing message role", "OUTPUT_STRUCTURE", root -> message(root).remove("role"));
        output(cases, "nonarray message content", "OUTPUT_STRUCTURE", root -> message(root).put("content", PRIVATE_BODY));
        output(cases, "unknown output text field", "OUTPUT_STRUCTURE", root -> outputText(root).put(PRIVATE_FIELD, PRIVATE_BODY));
        output(cases, "refusal content", "OUTPUT_STRUCTURE", root -> outputText(root).put("type", "refusal"));
        output(cases, "missing business text", "OUTPUT_STRUCTURE", root -> outputText(root).remove("text"));
        output(cases, "nonstring business text", "OUTPUT_STRUCTURE", root -> outputText(root).put("text", 1));
        output(cases, "blank business text", "OUTPUT_STRUCTURE", root -> outputText(root).put("text", " "));
        output(cases, "nonempty annotations", "OUTPUT_STRUCTURE", root -> outputText(root).putArray("annotations").addObject().put(PRIVATE_FIELD, PRIVATE_BODY));
        output(cases, "nonempty logprobs", "OUTPUT_STRUCTURE", root -> outputText(root).putArray("logprobs").add(PRIVATE_BODY));
        for (String business : List.of(PRIVATE_PARSER + " {", "[]", "null", "{} {}",
                "{\"" + PRIVATE_FIELD + "\":1,\"" + PRIVATE_FIELD + "\":2}")) {
            output(cases, "invalid business JSON " + cases.size(), "OUTPUT_JSON", root -> outputText(root).put("text", business));
        }
        String echoedBusiness = "{\"" + PRIVATE_FIELD + "\":\"prefix " + KEY + " suffix\"}";
        output(cases, "credential echoed in business text", "OUTPUT_CREDENTIAL_ECHO", root -> outputText(root).put("text", echoedBusiness));
        String escapedKey = KEY.chars().mapToObj(character -> String.format("\\u%04x", character)).collect(Collectors.joining());
        String escapedBusiness = echoedBusiness.replace(KEY, escapedKey);
        assertThat(escapedBusiness).doesNotContain(KEY);
        output(cases, "unicode-escaped credential in business text", "OUTPUT_CREDENTIAL_ECHO", root -> outputText(root).put("text", escapedBusiness));
        return cases.stream();
    }

    private static void envelope(List<RejectedResponse> cases, String name, String reason, Consumer<ObjectNode> change) throws Exception {
        changed(cases, name, "RESPONSE_ENVELOPE", reason, change, false);
    }

    private static void accounting(List<RejectedResponse> cases, String name, String reason, Consumer<ObjectNode> change) throws Exception {
        changed(cases, name, "RESPONSE_ACCOUNTING", reason, change, false);
    }

    private static void output(List<RejectedResponse> cases, String name, String reason, Consumer<ObjectNode> change) throws Exception {
        changed(cases, name, "RESPONSE_OUTPUT", reason, change, true);
    }

    private static void changed(List<RejectedResponse> cases, String name, String stage, String reason,
            Consumer<ObjectNode> change, boolean knownUsage) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(VALID_ENVELOPE);
        change.accept(root);
        cases.add(rawCase(name, stage, reason, MAPPER.writeValueAsString(root), knownUsage));
    }

    private static RejectedResponse rawCase(String name, String stage, String reason, String raw, boolean knownUsage) {
        return new RejectedResponse(name, stage, reason, "application/json", raw.getBytes(StandardCharsets.UTF_8), knownUsage, true);
    }

    private static RejectedResponse unknownRootCase(String name, ObjectNode root, int expectedCount) throws Exception {
        return new RejectedResponse(name, "RESPONSE_ENVELOPE", "ENVELOPE_UNKNOWN_FIELDS", "application/json",
            MAPPER.writeValueAsBytes(root), false, true, expectedCount);
    }

    private static ObjectNode usage(ObjectNode root) { return (ObjectNode) root.get("usage"); }
    private static ObjectNode inputDetails(ObjectNode root) { return (ObjectNode) root.at("/usage/input_tokens_details"); }
    private static ObjectNode outputDetails(ObjectNode root) { return (ObjectNode) root.at("/usage/output_tokens_details"); }
    private static ObjectNode message(ObjectNode root) { return (ObjectNode) root.at("/output/0"); }
    private static ObjectNode outputText(ObjectNode root) { return (ObjectNode) root.at("/output/0/content/0"); }

    private static void approvedMetadata(ObjectNode root) {
        root.putNull("user").putNull("max_tool_calls").put("top_logprobs", 0).put("completed_at", 2);
    }

    private static LiveResponsePort.Reply send(DeepSeekLiveResponseClient client) {
        return client.send(new byte[] {'{', '}'}, KEY, Duration.ofSeconds(1), Set.of("deepseek-flash"));
    }

    private static void assertTrustedUsage(LiveResponsePort.Reply reply) {
        assertThat(reply.usage()).isNotNull();
        assertThat(reply.usage().inputTokens()).isEqualTo(100);
        assertThat(reply.usage().outputTokens()).isEqualTo(20);
        assertThat(reply.usage().cachedInputTokens()).isEqualTo(40);
        assertThat(reply.usage().totalTokens()).isEqualTo(120);
        assertThat(reply.usage().reasoningTokens()).isZero();
        assertThat(reply.providerResponseId()).isEqualTo(PRIVATE_ID);
        assertThat(reply.actualModelId()).isEqualTo("deepseek-flash");
    }

    private void withIsolatedTrace(Runnable operation) {
        Map<String, String> workerContext = Map.of("traceId", "previous-worker-trace", "worker", PRIVATE_WORKER);
        MDC.setContextMap(workerContext);
        new TraceTaskScope().wrap(TRACE, operation).run();
        assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(workerContext);
    }

    private void assertDiagnostic(String stage, String reason) {
        assertDiagnostic(stage, reason, null);
    }

    private void assertDiagnostic(String stage, String reason, Integer unknownFieldCount) {
        assertThat(logs.list).as("one fixed diagnostic for a rejected provider response").hasSize(1);
        ILoggingEvent event = logs.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getLoggerName()).isEqualTo(DeepSeekLiveResponseClient.class.getName());
        assertThat(event.getThreadName()).isEqualTo(Thread.currentThread().getName());
        assertThat(event.getMessage()).isEqualTo("Agent provider response rejected");
        assertThat(event.getFormattedMessage()).isEqualTo("Agent provider response rejected");
        assertThat(event.getArgumentArray()).isNullOrEmpty();
        assertThat(event.getThrowableProxy()).isNull();
        var expectedPairs = new ArrayList<>(List.of(tuple("event", "agent.provider.response.rejected"),
            tuple("stage", stage), tuple("reason", reason)));
        if (unknownFieldCount != null) expectedPairs.add(tuple("unknownFieldCount", unknownFieldCount));
        assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key, pair -> pair.value)
            .containsExactlyInAnyOrderElementsOf(expectedPairs);
        assertThat(event.getMDCPropertyMap()).containsExactlyEntriesOf(Map.of("traceId", TRACE));
        String allDiagnosticChannels = event.getFormattedMessage() + Arrays.deepToString(event.getArgumentArray())
            + event.getKeyValuePairs() + event.getMDCPropertyMap();
        assertThat(allDiagnosticChannels).doesNotContain(KEY, PRIVATE_BODY, PRIVATE_FIELD, PRIVATE_CONTENT_TYPE,
            PRIVATE_MODEL, PRIVATE_ID, PRIVATE_PARSER, PRIVATE_WORKER, "deepseek-flash", "application/json",
            "Invalid DeepSeek wire payload", "Invalid DeepSeek business output");
    }

    private record RejectedResponse(String name, String stage, String reason, String contentType, byte[] body,
            boolean knownUsage, boolean wipedAfterClientReceivesBody, Integer unknownFieldCount) {
        RejectedResponse(String name, String stage, String reason, String contentType, byte[] body,
                boolean knownUsage, boolean wipedAfterClientReceivesBody) {
            this(name, stage, reason, contentType, body, knownUsage, wipedAfterClientReceivesBody, null);
        }
        @Override public String toString() { return name; }
    }
}
