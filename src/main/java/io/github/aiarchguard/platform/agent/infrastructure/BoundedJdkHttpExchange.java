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

/**
 * JDK HTTP 的有界传输机制：固定 TLS 主机校验、直连、禁重定向及安全 JVM 条件，异步接收限制 64 KiB 并在超时或中断时取消。
 * 实际生产目标由上层固定。
 */
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

    /** 异步读取响应并按调用方剩余期限等待；超时/中断立即请求取消 Future 和正文订阅，保留线程中断状态。 */
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

    /** 这些属性可能在 JDK 初始化时读取，只检查启动前已设置的安全条件，不能在创建客户端时悄悄补设并假定已生效。 */
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
        /** 逐块累计前先核对 64 KiB 上限，越界即终止订阅，避免完整缓冲任意大小响应后才检查。 */
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (cancelled) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > DeepSeekResponsesCodec.MAX_RESPONSE_BYTES - bytes.size()) {
                    result.completeExceptionally(new DeepSeekTransportFailure(DeepSeekResponseRejection.Reason.HTTP_BODY_LIMIT));
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
