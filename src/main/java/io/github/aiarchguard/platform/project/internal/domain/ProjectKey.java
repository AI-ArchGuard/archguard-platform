package io.github.aiarchguard.platform.project.internal.domain;

import io.github.aiarchguard.platform.project.InvalidProjectException;
import java.util.regex.Pattern;

public record ProjectKey(String value) {
    private static final Pattern VALID_KEY = Pattern.compile("^[a-z][a-z0-9-]{2,62}$");

    public ProjectKey {
        if (value == null || !VALID_KEY.matcher(value).matches()) {
            throw new InvalidProjectException(
                "Project key must be 3-63 lowercase letters, numbers, or hyphens, starting with a letter");
        }
    }
}
