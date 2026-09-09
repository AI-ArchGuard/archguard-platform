package io.github.aiarchguard.platform.identity.security;

import io.github.aiarchguard.platform.common.ApiErrorWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@Profile("oidc")
@EnableConfigurationProperties(OidcProperties.class)
class OidcSecurityConfiguration {

    @Bean
    SecurityFilterChain oidcSecurityFilterChain(HttpSecurity http, ApiErrorWriter errorWriter) throws Exception {
        PlatformSecurityConfiguration.configureDefaults(http, errorWriter);
        http.oauth2ResourceServer(oauth2 -> oauth2
            .authenticationEntryPoint((request, response, exception) -> errorWriter.write(
                response, 401, "authentication.required", "Authentication is required."))
            .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                response, 403, "authorization.denied",
                "The current actor is not allowed to perform this action."))
            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    @Bean
    JwtDecoder oidcJwtDecoder(OidcProperties properties) {
        NimbusJwtDecoder decoder = StringUtils.hasText(properties.jwkSetUri())
            ? NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build()
            : JwtDecoders.fromIssuerLocation(properties.issuerUri());
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(properties.issuerUri()),
            new OidcAudienceValidator(properties.audience()),
            new OidcActorSubjectValidator()));
        return decoder;
    }

    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        converter.setPrincipalClaimName(StandardClaimNames.SUB);
        return converter;
    }
}
