package io.github.aiarchguard.platform.identity.security;

import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("archguard.identity.oidc")
record OidcProperties(
    @NotBlank String issuerUri,
    @NotBlank String audience,
    String jwkSetUri
) {
    OidcProperties {
        requireHttps("issuer-uri", issuerUri);
        if (StringUtils.hasText(jwkSetUri)) {
            requireHttps("jwk-set-uri", jwkSetUri);
        }
    }

    private static void requireHttps(String property, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(property + " must be an absolute HTTPS URI");
        }
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(property + " must be an absolute HTTPS URI");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(property + " must be an absolute HTTPS URI", exception);
        }
    }
}
