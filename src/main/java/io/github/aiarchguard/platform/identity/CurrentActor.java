package io.github.aiarchguard.platform.identity;

import java.util.Set;
import java.util.UUID;

public record CurrentActor(UUID id, Set<String> permissions) {

    public CurrentActor {
        permissions = Set.copyOf(permissions);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
