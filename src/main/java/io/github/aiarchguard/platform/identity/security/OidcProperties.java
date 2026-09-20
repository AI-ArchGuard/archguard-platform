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
    String jwkSetUri,
    boolean allowHttp
) {
    OidcProperties {
        requireSecureUri("issuer-uri", issuerUri, allowHttp);
        if (StringUtils.hasText(jwkSetUri)) {
            requireSecureUri("jwk-set-uri", jwkSetUri, allowHttp);
        }
    }

    private static void requireSecureUri(String property, String value, boolean allowHttp) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(property + " must be an absolute HTTPS URI");
        }
        try {
            URI uri = URI.create(value);
            boolean acceptedScheme = "https".equalsIgnoreCase(uri.getScheme())
                || (allowHttp && "http".equalsIgnoreCase(uri.getScheme()));
            if (!uri.isAbsolute() || !acceptedScheme
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(property + " must be an absolute HTTPS URI");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(property + " must be an absolute HTTPS URI", exception);
        }
    }
}
