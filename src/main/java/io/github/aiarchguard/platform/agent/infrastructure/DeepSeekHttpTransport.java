package io.github.aiarchguard.platform.agent.infrastructure;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

/** Unregistered infrastructure building block, not a model port or an authorization policy. */
final class DeepSeekHttpTransport {
    private static final URI ENDPOINT = URI.create("https://api.deepseek.com/responses");
    private final Exchange exchange;

    DeepSeekHttpTransport(Exchange exchange) { this.exchange = exchange; }

    byte[] post(byte[] body, String key, Duration timeout) {
        if (body == null || body.length == 0 || body.length > 6000 || key == null
                || !key.matches("[A-Za-z0-9._-]{16,256}") || timeout == null
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new DeepSeekTransportFailure("MODEL_UNAVAILABLE");
        }
        try {
            var request = HttpRequest.newBuilder(ENDPOINT).timeout(timeout)
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            Response response = exchange.exchange(request, timeout);
            if (response == null || response.status() != 200) throw new DeepSeekTransportFailure("MODEL_UNAVAILABLE");
            if (response.contentType() == null || !response.contentType().matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")
                    || response.body() == null || response.body().length == 0
                    || response.body().length > DeepSeekResponsesCodec.MAX_RESPONSE_BYTES) {
                throw new DeepSeekTransportFailure("OUTPUT_INVALID");
            }
            return response.body();
        } catch (DeepSeekTransportFailure safe) {
            throw safe;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DeepSeekTransportFailure("MODEL_TIMEOUT");
        } catch (Exception failure) {
            throw new DeepSeekTransportFailure("MODEL_UNAVAILABLE");
        }
    }

    @FunctionalInterface interface Exchange {
        Response exchange(HttpRequest request, Duration timeout) throws Exception;
    }

    record Response(int status, String contentType, byte[] body) {
        @Override public String toString() { return "DeepSeek HTTP response [body omitted]"; }
    }
}
