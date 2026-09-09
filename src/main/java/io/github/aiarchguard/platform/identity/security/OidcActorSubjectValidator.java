package io.github.aiarchguard.platform.identity.security;

import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

final class OidcActorSubjectValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error INVALID_SUBJECT = new OAuth2Error(
        "invalid_token", "The token subject is not a valid actor identifier.", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            UUID.fromString(token.getSubject());
            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException | NullPointerException exception) {
            return OAuth2TokenValidatorResult.failure(INVALID_SUBJECT);
        }
    }
}
