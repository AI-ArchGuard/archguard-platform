package io.github.aiarchguard.platform.agent.infrastructure;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * 固定官方 HTTPS Responses 地址的 HTTP 构造与响应边界；
 * 限制完整请求、Key 形状、剩余期限及 JSON 响应，外部异常转固定错误，不承担业务授权。
 */
final class DeepSeekHttpTransport {
    private static final URI ENDPOINT = URI.create("https://api.deepseek.com/responses");
    private final Exchange exchange;

    DeepSeekHttpTransport(Exchange exchange) { this.exchange = exchange; }

    /**
     * 只构造固定目标的一次 POST，限制请求、凭据形状和剩余期限；状态、Content-Type、正文边界通过后才交给编解码器。
     */
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
            if (response.contentType() == null || !response.contentType().matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")) {
                throw new DeepSeekTransportFailure(DeepSeekResponseRejection.Reason.HTTP_CONTENT_TYPE);
            }
            if (response.body() == null || response.body().length == 0) {
                throw new DeepSeekTransportFailure(DeepSeekResponseRejection.Reason.HTTP_BODY_EMPTY);
            }
            if (response.body().length > DeepSeekResponsesCodec.MAX_RESPONSE_BYTES) {
                throw new DeepSeekTransportFailure(DeepSeekResponseRejection.Reason.HTTP_BODY_LIMIT);
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
