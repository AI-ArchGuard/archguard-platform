package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.internal.LiveAdapterBundle;
import io.github.aiarchguard.platform.agent.internal.LiveExecutionCoordinator;
import io.github.aiarchguard.platform.agent.internal.LiveResponsePort;
import io.github.aiarchguard.platform.agentcredential.CredentialUseSource;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Test-only fake HTTP exchange; production codec, client and version-bound credential bridge remain real. */
public final class SyntheticRuntimeLiveAdapter implements LiveAdapterBundle {
    public static final String KEY = "synthetic-end-to-end-runtime-credential-only";
    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicReference<String> mode = new AtomicReference<>("SUCCESS");
    public final List<String> bodies = new CopyOnWriteArrayList<>();
    public volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    public volatile CountDownLatch credentialEntered = new CountDownLatch(1), credentialRelease = new CountDownLatch(1), credentialFinished = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ObjectMapper mapper;
    private final LiveResponsePort responses;
    private final LiveExecutionCoordinator.CredentialUse credentialBridge;

    public SyntheticRuntimeLiveAdapter(ObjectMapper mapper, CredentialUseSource source) {
        this.mapper = mapper;
        responses = new DeepSeekLiveResponseClient(mapper, this::exchange);
        var real = new VersionBoundCredentialUse(source);
        credentialBridge = new LiveExecutionCoordinator.CredentialUse() {
            @Override public LiveResponsePort.Reply use(java.util.UUID version, Runnable preflight, Function<String, LiveResponsePort.Reply> send) {
                throw new AssertionError("The assembled runtime must not use the legacy unbounded credential bridge");
            }
            @Override public LiveResponsePort.Reply use(java.util.UUID version, LongSupplier remaining, Runnable preflight,
                    Function<String, LiveResponsePort.Reply> send) {
                // Hold the worker before the credential slot, so a second explicit request can
                // be created and queued without this fixture blocking its metadata read.
                if ("BEFORE_KEY".equals(mode.get())) { entered.countDown(); await(release); }
                if (!"AUDIT_LOCK".equals(mode.get())) return real.use(version, remaining, preflight, send);
                // This fault seam only shortens the actual original worker budget; it never renews it.
                var shortenedEnd = new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
                LongSupplier tightened = () -> Math.min(remaining.getAsLong(), shortenedEnd.get() - System.nanoTime());
                try {
                    return real.use(version, tightened, () -> {
                        preflight.run(); credentialEntered.countDown(); await(credentialRelease);
                        shortenedEnd.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
                    }, send);
                } finally { credentialFinished.countDown(); }
            }
        };
    }

    @Override public LiveResponsePort responses() { return responses; }
    @Override public LiveExecutionCoordinator.CredentialUse credentials() { return credentialBridge; }
    @Override public void close() { closed.set(true); release.countDown(); credentialRelease.countDown(); }
    @Override public boolean awaitTermination(Duration timeout) { return true; } // This fake exchange owns no I/O threads or sockets.

    private DeepSeekHttpTransport.Response exchange(HttpRequest request, Duration remaining) throws Exception {
        assertThat(closed).isFalse(); assertThat(request.uri()).isEqualTo(URI.create("https://api.deepseek.com/responses"));
        assertThat(request.method()).isEqualTo("POST"); assertThat(remaining).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(30));
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer " + KEY);
        byte[] bytes = requestBytes(request); assertThat(bytes.length).isLessThanOrEqualTo(6000);
        String body = new String(bytes, StandardCharsets.UTF_8); bodies.add(body);
        JsonNode wire = mapper.readTree(body);
        assertThat(wire.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(wire.path("max_output_tokens").asInt()).isEqualTo(1500);
        assertThat(wire.at("/reasoning/effort").asText()).isEqualTo("none");
        assertThat(wire.path("tool_choice").asText()).isEqualTo("none"); assertThat(wire.path("store").asBoolean()).isFalse();
        assertThat(wire.has("tools")).isFalse(); assertThat(body).doesNotContain(KEY);
        int call = calls.incrementAndGet(); entered.countDown();
        if ("BLOCKED".equals(mode.get())) await(release);
        if ("HTTP_ERROR".equals(mode.get())) return new DeepSeekHttpTransport.Response(503, "application/json", new byte[0]);
        AgentModelPort.ModelInput input = mapper.readValue(wire.path("input").asText(), AgentModelPort.ModelInput.class);
        var output = output(input);
        if ("BAD_CITATION".equals(mode.get())) ((ObjectNode) output.path("conclusion")).putArray("citationIds").add("fabricated-reference");
        ObjectNode envelope = mapper.createObjectNode().put("id", "resp-fake-http-" + call).put("object", "response")
            .put("status", "completed").put("model", "deepseek-flash");
        envelope.putArray("output").addObject().put("id", "msg-fake-http-" + call).put("type", "message")
            .put("role", "assistant").put("status", "completed").putArray("content").addObject()
            .put("type", "output_text").put("text", mapper.writeValueAsString(output)).putArray("annotations");
        var usage = envelope.putObject("usage").put("input_tokens", 1000).put("output_tokens", 100).put("total_tokens", 1100);
        usage.putObject("input_tokens_details").put("cached_tokens", 500); usage.putObject("output_tokens_details").put("reasoning_tokens", 0);
        return new DeepSeekHttpTransport.Response(200, "application/json", mapper.writeValueAsBytes(envelope));
    }

    private ObjectNode output(AgentModelPort.ModelInput input) {
        boolean summary = "PR_SUMMARY".equals(input.purpose());
        String finding = summary ? input.findings().getFirst().findingRef() : input.findingRef();
        String evidence = summary ? input.findings().getFirst().evidence().getFirst().citationId() : input.evidence().getFirst().citationId();
        var root = mapper.createObjectNode().put("schemaVersion", "0.1.0").put("purpose", input.purpose());
        var citations = root.putObject("conclusion").put("kind", "SUPPORTED").put("text", "Synthetic dependency evidence warrants human review.")
            .putArray("citationIds").add(evidence);
        if (!input.documents().isEmpty()) citations.add(input.documents().getFirst().citationId());
        for (String field : List.of("claims", "ruleBasis")) {
            root.putArray(field).addObject().put("findingRef", finding).put("text", "The selected rule identifies the dependency.")
                .putArray("citationIds").add(evidence);
        }
        var suggestion = root.putArray("suggestions").addObject().put("kind", "HUMAN_VERIFICATION")
            .put("text", "Verify the dependency boundary.").put("requiresHumanReview", true);
        suggestion.putArray("findingRefs").add(finding); suggestion.putArray("citationIds").add(evidence);
        root.putArray("limitations"); return root;
    }

    private static byte[] requestBytes(HttpRequest request) throws Exception {
        var bytes = new ByteArrayOutputStream(); var done = new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) { byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk); }
            @Override public void onError(Throwable error) { done.completeExceptionally(error); }
            @Override public void onComplete() { done.complete(bytes.toByteArray()); }
        });
        return done.get(2, TimeUnit.SECONDS);
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic exchange gate timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Synthetic exchange interrupted"); }
    }
}
