package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DeepSeekHttpTransportTest {
    @Test void fixesOfficialTargetAndHeadersWithoutProvidingAnEndpointSetting() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        var transport = new DeepSeekHttpTransport((request, timeout) -> {
            captured.set(request);
            return new DeepSeekHttpTransport.Response(200, "application/json; charset=utf-8", "{}".getBytes(StandardCharsets.UTF_8));
        });
        assertThat(transport.post("{}".getBytes(StandardCharsets.UTF_8), "synthetic-test-key", Duration.ofSeconds(1)))
            .containsExactly((byte) '{', (byte) '}');
        assertThat(captured.get().uri()).isEqualTo(URI.create("https://api.deepseek.com/responses"));
        assertThat(captured.get().method()).isEqualTo("POST");
        assertThat(captured.get().headers().firstValue("Authorization")).contains("Bearer synthetic-test-key");
        assertThat(captured.get().headers().firstValue("Content-Type")).contains("application/json");
    }

    @Test void rejectsBadCredentialsAndLimitsBeforeAnyExchange() {
        AtomicInteger calls = new AtomicInteger();
        var transport = new DeepSeekHttpTransport((request, timeout) -> { calls.incrementAndGet(); throw new AssertionError(); });
        for (String key : new String[] {"", "short", "synthetic-test-key\r\nheader", " synthetic-test-key"}) {
            assertThatThrownBy(() -> transport.post(new byte[1], key, Duration.ofSeconds(1)))
                .isInstanceOf(DeepSeekTransportFailure.class).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        }
        for (Duration timeout : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(31)}) {
            assertThatThrownBy(() -> transport.post(new byte[1], "synthetic-test-key", timeout))
                .isInstanceOf(DeepSeekTransportFailure.class);
        }
        assertThatThrownBy(() -> transport.post(new byte[6001], "synthetic-test-key", Duration.ofSeconds(1)))
            .isInstanceOf(DeepSeekTransportFailure.class);
        assertThat(calls.get()).isZero();
    }

    @Test void localFakeHttpDoesNotFollowRedirectOrRetryErrors() throws Exception {
        for (int status : new int[] {302, 401, 429, 500, 503}) {
            AtomicInteger calls = new AtomicInteger(), redirected = new AtomicInteger();
            HttpServer server = server();
            server.createContext("/responses", exchange -> {
                calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Location", "/redirected");
                byte[] body = "private-response-body".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.createContext("/redirected", exchange -> { redirected.incrementAndGet(); exchange.close(); });
            server.start();
            try (var http = new BoundedJdkHttpExchange()) {
                var transport = localTransport(server, http);
                assertThatThrownBy(() -> transport.post(new byte[1], "synthetic-test-key", Duration.ofSeconds(2)))
                    .isInstanceOf(DeepSeekTransportFailure.class).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
                assertThat(calls.get()).isEqualTo(1);
                assertThat(redirected.get()).isZero();
            } finally { server.stop(0); }
        }
    }

    @Test void limitsChunkedBodiesAndTerminatesClientResources() throws Exception {
        HttpServer server = server();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/responses", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) { out.write(new byte[65537]); }
            catch (java.io.IOException closedByClient) { /* Expected cancellation of oversized body. */ }
        });
        server.start();
        var http = new BoundedJdkHttpExchange();
        try {
            assertThatThrownBy(() -> localTransport(server, http).post(new byte[1], "synthetic-test-key", Duration.ofSeconds(2)))
                .isInstanceOf(DeepSeekTransportFailure.class).hasMessage("OUTPUT_INVALID").hasNoCause();
            assertThat(calls.get()).isEqualTo(1);
        } finally { http.close(); server.stop(0); }
        assertThat(http.awaitTermination(Duration.ofSeconds(3))).isTrue();
    }

    @Test void deadlineCoversStalledBodyAndReleasesClientResources() throws Exception {
        HttpServer server = server();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        CountDownLatch headersSent = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/responses", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write('{'); out.flush(); headersSent.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Fixture was not released");
                out.write('}');
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException closedByClient) { /* Expected connection cancellation. */ }
        });
        server.start();
        var http = new BoundedJdkHttpExchange();
        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> localTransport(server, http).post(new byte[1], "synthetic-test-key", Duration.ofMillis(700)))
                .isInstanceOf(DeepSeekTransportFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
            assertThat(headersSent.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            assertThat(calls.get()).isEqualTo(1);
            http.close();
            // The fixture is still stalled/open: server shutdown cannot mask a client resource leak.
            assertThat(http.awaitTermination(Duration.ofMillis(500))).isTrue();
        } finally { release.countDown(); http.close(); server.stop(0); executor.close(); }
        assertThat(http.awaitTermination(Duration.ofSeconds(3))).isTrue();
    }

    @Test void refusesNonJsonResponsesAndHidesSenderExceptions() throws Exception {
        var wrongType = new DeepSeekHttpTransport((request, timeout) -> new DeepSeekHttpTransport.Response(200,
            "text/html", "private-body".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> wrongType.post(new byte[1], "synthetic-test-key", Duration.ofSeconds(1)))
            .hasMessage("OUTPUT_INVALID").hasNoCause();
        var failure = new DeepSeekHttpTransport((request, timeout) -> { throw new java.io.IOException("private-key private-body"); });
        assertThatThrownBy(() -> failure.post(new byte[1], "synthetic-test-key", Duration.ofSeconds(1)))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
    }

    @Test void closedConnectionDoesNotRetryThePost() throws Exception {
        HttpServer server = server();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/responses", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes(); exchange.close();
        });
        server.start();
        try (var http = new BoundedJdkHttpExchange()) {
            assertThatThrownBy(() -> localTransport(server, http).post(new byte[1], "synthetic-test-key", Duration.ofSeconds(2)))
                .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
            assertThat(calls.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    @Test void callerInterruptionCancelsIoAndPreservesInterruptStatus() throws Exception {
        HttpServer server = server();
        var handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext("/responses", exchange -> {
            exchange.getRequestBody().readAllBytes(); received.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var http = new BoundedJdkHttpExchange();
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<RuntimeException>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var transport = localTransport(server, http);
        var caller = Thread.startVirtualThread(() -> {
            try { transport.post(new byte[1], "synthetic-test-key", Duration.ofSeconds(5)); }
            catch (RuntimeException error) { failure.set(error); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); done.countDown(); }
        });
        try {
            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isInstanceOf(DeepSeekTransportFailure.class).hasMessage("MODEL_TIMEOUT");
            assertThat(interrupted.get()).isTrue();
            http.close();
            assertThat(http.awaitTermination(Duration.ofMillis(500))).isTrue();
        } finally {
            caller.interrupt(); release.countDown(); http.close(); server.stop(0); handlers.close();
        }
        assertThat(http.awaitTermination(Duration.ofSeconds(3))).isTrue();
    }

    @Test void ignoresInheritedProxyAndRefusesUnsafeJvmSettings() throws Exception {
        var original = java.net.ProxySelector.getDefault();
        try {
            java.net.ProxySelector.setDefault(new java.net.ProxySelector() {
                @Override public java.util.List<java.net.Proxy> select(URI uri) { throw new AssertionError("Inherited proxy used"); }
                @Override public void connectFailed(URI uri, java.net.SocketAddress address, java.io.IOException failure) { }
            });
            try (var http = new BoundedJdkHttpExchange()) {
                assertThat(new BoundedJdkHttpExchange.DirectOnlyProxySelector().select(URI.create("https://api.deepseek.com")))
                    .containsExactly(java.net.Proxy.NO_PROXY);
            }
        } finally { java.net.ProxySelector.setDefault(original); }
        for (String[] setting : new String[][] {
                {"jdk.httpclient.disableRetryConnect", "false"}, {"jdk.httpclient.enableAllMethodRetry", "true"},
                {"jdk.internal.httpclient.disableHostnameVerification", ""}, {"jdk.httpclient.HttpClient.log", "headers,content"}}) {
            String old = System.getProperty(setting[0]);
            try {
                System.setProperty(setting[0], setting[1]);
                assertThatThrownBy(BoundedJdkHttpExchange::requireSafeJvm).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
            } finally {
                if (old == null) System.clearProperty(setting[0]); else System.setProperty(setting[0], old);
            }
        }
    }

    private HttpServer server() throws Exception { return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }

    // Test-only target rewrite: production DeepSeekHttpTransport has no target override.
    private DeepSeekHttpTransport localTransport(HttpServer server, BoundedJdkHttpExchange http) {
        return new DeepSeekHttpTransport((request, timeout) -> {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"))
                .timeout(timeout).POST(request.bodyPublisher().orElseThrow());
            request.headers().map().forEach((key, values) -> values.forEach(value -> builder.header(key, value)));
            return http.exchange(builder.build(), timeout);
        });
    }
}
