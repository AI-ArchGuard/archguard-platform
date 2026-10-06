package io.github.aiarchguard.platform.agent.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** Bounded HTTP mechanics. Only DeepSeekHttpTransport supplies a production destination. */
final class BoundedJdkHttpExchange implements DeepSeekHttpTransport.Exchange, AutoCloseable {
    private final HttpClient client;

    BoundedJdkHttpExchange() {
        requireSafeJvm();
        try {
            var ssl = SSLContext.getInstance("TLS");
            ssl.init(null, null, null);
            var parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            client = HttpClient.newBuilder().sslContext(ssl).sslParameters(parameters)
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).proxy(new DirectOnlyProxySelector()).build();
        } catch (Exception failure) {
            throw new DeepSeekTransportFailure("MODEL_UNAVAILABLE");
        }
    }

    @Override public DeepSeekHttpTransport.Response exchange(HttpRequest request, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new DeepSeekTransportFailure("MODEL_UNAVAILABLE");
        }
        AtomicReference<BoundedBody> body = new AtomicReference<>();
        CompletableFuture<HttpResponse<byte[]>> call = client.sendAsync(request, info -> {
            var subscriber = new BoundedBody(info.statusCode() == 200 ? null : "MODEL_UNAVAILABLE");
            body.set(subscriber);
            return subscriber;
        });
        try {
            var response = call.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return new DeepSeekHttpTransport.Response(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(null), response.body());
        } catch (TimeoutException timeoutFailure) {
            throw new DeepSeekTransportFailure("MODEL_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DeepSeekTransportFailure("MODEL_TIMEOUT");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof DeepSeekTransportFailure safe) throw safe;
            if (cause instanceof java.net.http.HttpTimeoutException) throw new DeepSeekTransportFailure("MODEL_TIMEOUT");
            throw new DeepSeekTransportFailure("MODEL_UNAVAILABLE");
        } finally {
            call.cancel(true);
            var subscriber = body.get();
            if (subscriber != null) subscriber.cancel();
        }
    }

    static void requireSafeJvm() {
        // These JDK properties are read at JVM/client initialization: never silently set them late.
        if (!"true".equals(System.getProperty("jdk.httpclient.disableRetryConnect"))
                || !isFalseOrAbsent("jdk.httpclient.enableAllMethodRetry")
                || !isFalseOrAbsent("jdk.internal.httpclient.disableHostnameVerification")
                || System.getProperty("jdk.httpclient.HttpClient.log") != null
                    && !System.getProperty("jdk.httpclient.HttpClient.log").isBlank()) {
            throw new DeepSeekTransportFailure("MODEL_UNAVAILABLE");
        }
    }

    private static boolean isFalseOrAbsent(String key) {
        String value = System.getProperty(key);
        return value == null || "false".equals(value);
    }

    @Override public void close() { client.shutdownNow(); }
    boolean awaitTermination(Duration timeout) throws InterruptedException { return client.awaitTermination(timeout); }

    static final class DirectOnlyProxySelector extends ProxySelector {
        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        @Override public void connectFailed(URI uri, SocketAddress address, IOException exception) { }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean cancelled;

        BoundedBody(String earlyFailure) {
            if (earlyFailure != null) {
                cancelled = true;
                result.completeExceptionally(new DeepSeekTransportFailure(earlyFailure));
            }
        }

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public synchronized void onSubscribe(Flow.Subscription incoming) {
            if (cancelled || subscription != null) { incoming.cancel(); return; }
            subscription = incoming;
            incoming.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (cancelled) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > DeepSeekResponsesCodec.MAX_RESPONSE_BYTES - bytes.size()) {
                    result.completeExceptionally(new DeepSeekTransportFailure("OUTPUT_INVALID"));
                    cancel(); return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable failure) {
            result.completeExceptionally(new DeepSeekTransportFailure("MODEL_UNAVAILABLE"));
            cancel();
        }
        @Override public synchronized void onComplete() { result.complete(bytes.toByteArray()); }
        synchronized void cancel() {
            cancelled = true;
            if (subscription != null) subscription.cancel();
            if (!result.isDone()) result.completeExceptionally(new DeepSeekTransportFailure("MODEL_TIMEOUT"));
        }
    }
}
