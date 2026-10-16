package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicDeepSeekExchangeTest {
    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
    }
    private static DeepSeekHttpTransport.Response reply() {
        return new DeepSeekHttpTransport.Response(200, "application/json", new byte[0]);
    }

    @ParameterizedTest @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "10.1.2.3", "100.64.0.1",
        "169.254.169.254", "172.16.1.1", "192.0.0.9", "192.0.2.1", "192.88.99.1", "192.168.1.1",
        "198.18.1.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
        "::", "::1", "::ffff:127.0.0.1", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1", "2002:a00:1::", "3fff::1"})
    void anyNonPublicDnsAnswerPreventsHttp(String address) throws Exception {
        var calls = new AtomicInteger();
        try (var exchange = new PublicDeepSeekExchange((r, d) -> { calls.incrementAndGet(); return reply(); },
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8"), InetAddress.getByName(address)})) {
            assertThatThrownBy(() -> exchange.exchange(request("https://api.deepseek.com/responses"), Duration.ofSeconds(1)))
                .isInstanceOf(DeepSeekTransportFailure.class).hasMessage("MODEL_UNAVAILABLE");
            assertThat(calls.get()).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"http://api.deepseek.com/responses", "https://other.invalid/responses",
        "https://api.deepseek.com:444/responses", "https://api.deepseek.com/responses?key=x",
        "https://user@api.deepseek.com/responses", "https://api.deepseek.com/other"})
    void destinationCannotBeOverriddenEvenBeforeDns(String url) {
        var dns = new AtomicInteger(); var calls = new AtomicInteger();
        try (var exchange = new PublicDeepSeekExchange((r, d) -> { calls.incrementAndGet(); return reply(); },
                host -> { dns.incrementAndGet(); return new InetAddress[] {InetAddress.getByName("8.8.8.8")}; })) {
            assertThatThrownBy(() -> exchange.exchange(request(url), Duration.ofSeconds(1))).isInstanceOf(DeepSeekTransportFailure.class);
            assertThat(dns.get()).isZero(); assertThat(calls.get()).isZero();
        }
    }

    @Test void fixedPublicAnswersAreCheckedWithoutNetworkProbeAndOnlyRemainingBudgetIsPassed() {
        var calls = new AtomicInteger(); var budget = new AtomicReference<Duration>();
        try (var exchange = new PublicDeepSeekExchange((r, d) -> { calls.incrementAndGet(); budget.set(d); return reply(); },
                host -> { assertThat(host).isEqualTo("api.deepseek.com"); return new InetAddress[] {InetAddress.getByName("8.8.8.8")}; })) {
            assertThat(exchange.exchange(request("https://api.deepseek.com/responses"), Duration.ofSeconds(1)).status()).isEqualTo(200);
            assertThat(calls.get()).isOne();
            assertThat(budget.get()).isPositive().isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test void dnsTimeoutCannotSendLaterOrAccumulateResolverTasks() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        try (var exchange = new PublicDeepSeekExchange((r, d) -> { calls.incrementAndGet(); return reply(); }, host -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(25, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { /* Simulated OS resolver ignores interruption. */ }
            }
            return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
        })) {
            try {
                assertThatThrownBy(() -> exchange.exchange(request("https://api.deepseek.com/responses"), Duration.ofMillis(50)))
                    .isInstanceOf(DeepSeekTransportFailure.class).hasMessage("MODEL_TIMEOUT");
                assertThat(entered.getCount()).isZero();
                assertThatThrownBy(() -> exchange.exchange(request("https://api.deepseek.com/responses"), Duration.ofMillis(50)))
                    .isInstanceOf(DeepSeekTransportFailure.class);
                assertThat(calls.get()).isZero();
            } finally { release.countDown(); }
        }
        assertThat(calls.get()).isZero();
    }

    @Test void closedExchangeCannotResolveOrSend() throws Exception {
        var dns = new AtomicInteger(); var calls = new AtomicInteger();
        var exchange = new PublicDeepSeekExchange((r, d) -> { calls.incrementAndGet(); return reply(); },
            host -> { dns.incrementAndGet(); return new InetAddress[] {InetAddress.getByName("8.8.8.8")}; });
        exchange.close();
        assertThatThrownBy(() -> exchange.exchange(request("https://api.deepseek.com/responses"), Duration.ofSeconds(1)))
            .isInstanceOf(DeepSeekTransportFailure.class);
        assertThat(dns.get()).isZero(); assertThat(calls.get()).isZero();
        assertThat(exchange.awaitTermination(Duration.ofSeconds(1))).isTrue();
    }
}
