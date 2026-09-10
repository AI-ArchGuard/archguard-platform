package io.github.aiarchguard.platform.identity.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

final class OidcAudienceValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error INVALID_AUDIENCE = new OAuth2Error(
        "invalid_token", "The token audience is not accepted.", null);

    private final String audience;

    OidcAudienceValidator(String audience) {
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return token.getAudience().contains(audience)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE);
    }
}
