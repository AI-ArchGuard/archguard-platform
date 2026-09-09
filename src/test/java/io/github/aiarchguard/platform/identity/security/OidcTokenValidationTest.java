package io.github.aiarchguard.platform.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class OidcTokenValidationTest {
    private static final String ACTOR = "11111111-1111-1111-1111-111111111111";

    @Test
    void requiresTheConfiguredAudience() {
        OidcAudienceValidator validator = new OidcAudienceValidator("archguard-platform");

        assertThat(validator.validate(jwt(ACTOR, List.of("archguard-platform"))).hasErrors()).isFalse();
        assertThat(validator.validate(jwt(ACTOR, List.of("another-api"))).hasErrors()).isTrue();
    }

    @Test
    void requiresUuidSubjects() {
        OidcActorSubjectValidator validator = new OidcActorSubjectValidator();

        assertThat(validator.validate(jwt(ACTOR, List.of("archguard-platform"))).hasErrors()).isFalse();
        assertThat(validator.validate(jwt("not-a-uuid", List.of("archguard-platform"))).hasErrors()).isTrue();
    }

    @Test
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
    void rejectsNonHttpsIdentityEndpoints() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new OidcProperties("http://identity.example.test", "archguard-platform", null))
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
}
