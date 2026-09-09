package io.github.aiarchguard.platform.project.internal.domain;

import io.github.aiarchguard.platform.project.InvalidProjectException;
import java.text.Normalizer;

public record ProjectName(String value) {
    public ProjectName {
        if (value == null) {
            throw new InvalidProjectException("Project name is required");
        }
        value = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        int length = value.codePointCount(0, value.length());
        if (length == 0 || length > 120 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidProjectException("Project name must contain 1-120 characters without control characters");
        }
    }
}
