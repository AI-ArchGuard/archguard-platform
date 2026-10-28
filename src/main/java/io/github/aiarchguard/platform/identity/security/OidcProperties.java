package io.github.aiarchguard.platform.identity.security;

import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/** OIDC 启动参数及 URI 结构校验；allowHttp 还需由安全配置限制为 local-compose。 */
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

    /** 禁止相对地址、用户信息与片段；这里只验证结构，不探测远端身份服务。 */
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
