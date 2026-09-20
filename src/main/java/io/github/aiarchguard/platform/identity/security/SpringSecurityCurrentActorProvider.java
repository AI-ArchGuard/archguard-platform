package io.github.aiarchguard.platform.identity.security;

import io.github.aiarchguard.platform.identity.CurrentActor;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.identity.InvalidCurrentActorException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
class SpringSecurityCurrentActorProvider implements CurrentActorProvider {

    @Override
    public CurrentActor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
            || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken) {
            throw new IllegalStateException("An authenticated actor is required");
        }

        UUID actorId;
        try {
            actorId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw new InvalidCurrentActorException();
        }
        Set<String> permissions = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .collect(Collectors.toUnmodifiableSet());
        return new CurrentActor(actorId, permissions);
    }
}
