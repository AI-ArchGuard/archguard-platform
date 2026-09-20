package io.github.aiarchguard.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "archguard.identity.oidc.issuer-uri=https://identity.example.test",
    "archguard.identity.oidc.audience=archguard-platform",
    "archguard.identity.oidc.jwk-set-uri=https://identity.example.test/jwks"
})
@AutoConfigureMockMvc
@ActiveProfiles("oidc")
@Import(OidcSecurityIntegrationTest.TestJwtDecoderConfiguration.class)
class OidcSecurityIntegrationTest extends PostgresIntegrationTestSupport {
    private static final String ACTOR = "11111111-1111-1111-1111-111111111111";

    private final MockMvc mockMvc;

    @Autowired
    OidcSecurityIntegrationTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void acceptsAValidatedBearerTokenAndMapsItsScopes() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                .header("Authorization", "Bearer valid-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void returnsTheUniformAuthenticationErrorForInvalidBearerTokens() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                .header("Authorization", "Bearer invalid-token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("authentication.required"))
            .andExpect(jsonPath("$.traceId").isString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwtDecoderConfiguration {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> {
                if (!"valid-token".equals(token)) {
                    throw new BadJwtException("invalid test token");
                }
                Instant now = Instant.now();
                return new Jwt(token, now.minusSeconds(1), now.plusSeconds(300),
                    Map.of("alg", "RS256"),
                    Map.of(
                        "iss", "https://identity.example.test",
                        "sub", ACTOR,
                        "aud", List.of("archguard-platform"),
                        "scope", "project:create"));
            };
        }
    }
}
