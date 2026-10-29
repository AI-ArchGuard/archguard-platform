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

/** 从已经认证的 Spring Security 上下文提取 UUID 与权限，业务层不接收请求体中的 actorId 作为身份。 */
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

        // 统一身份标识格式，既适用于 JWT 请求，也适用于受控的一次性后台任务。
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
