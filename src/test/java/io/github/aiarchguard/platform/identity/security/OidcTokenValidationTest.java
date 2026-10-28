package io.github.aiarchguard.platform.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 验证本地 JWT 验证器及权限映射中的 audience、UUID subject 与 HTTPS 端点。直接构造的 JWT 不证明真实签名或发现服务可用。
 */
class OidcTokenValidationTest {
    private static final String ACTOR = "11111111-1111-1111-1111-111111111111";

    @Test
    // token 必须包含配置 audience，避免误用其他服务 token。
    void requiresTheConfiguredAudience() {
        OidcAudienceValidator validator = new OidcAudienceValidator("archguard-platform");

        assertThat(validator.validate(jwt(ACTOR, List.of("archguard-platform"))).hasErrors()).isFalse();
        assertThat(validator.validate(jwt(ACTOR, List.of("another-api"))).hasErrors()).isTrue();
        assertThat(validator.validate(jwtWithoutAudience(ACTOR)).hasErrors()).isTrue();
    }

    @Test
    // 已验证 subject 必须能映射为 UUID 业务身份。
    void requiresUuidSubjects() {
        OidcActorSubjectValidator validator = new OidcActorSubjectValidator();

        assertThat(validator.validate(jwt(ACTOR, List.of("archguard-platform"))).hasErrors()).isFalse();
        assertThat(validator.validate(jwt("not-a-uuid", List.of("archguard-platform"))).hasErrors()).isTrue();
    }

    @Test
    // scope/scp 统一为应用权限名，不携带框架前缀。
    void mapsStandardScopesToUnprefixedApplicationPermissions() {
        var authentication = OidcSecurityConfiguration.jwtAuthenticationConverter()
            .convert(jwt(ACTOR, List.of("archguard-platform")));

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo(ACTOR);
        assertThat(authentication.getAuthorities())
            .extracting(authority -> authority.getAuthority())
            .containsExactlyInAnyOrder("project:create", "profile:read");
    }

    @Test
    // issuer 与 JWKS 地址均须 HTTPS，防止配置降级。
    void rejectsNonHttpsIdentityEndpoints() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new OidcProperties("http://identity.example.test", "archguard-platform", null, false))
            .withMessageContaining("issuer-uri");
    }

    private static Jwt jwt(String subject, List<String> audience) {
        Instant now = Instant.now();
        return new Jwt("token", now.minusSeconds(1), now.plusSeconds(300),
            Map.of("alg", "RS256"),
            Map.of(
                "iss", "https://identity.example.test",
                "sub", subject,
                "aud", audience,
                "scope", "project:create profile:read"));
    }

    private static Jwt jwtWithoutAudience(String subject) {
        Instant now = Instant.now();
        return new Jwt("token", now.minusSeconds(1), now.plusSeconds(300),
            Map.of("alg", "RS256"),
            Map.of(
                "iss", "https://identity.example.test",
                "sub", subject,
                "scope", "project:create profile:read"));
    }
}
