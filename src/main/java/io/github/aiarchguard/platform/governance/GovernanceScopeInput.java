package io.github.aiarchguard.platform.governance;

import java.text.Normalizer;

public final class GovernanceScopeInput {
    private GovernanceScopeInput() { }

    public static String branch(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || !value.equals(value.trim())
            || value.startsWith("refs/heads/") || value.startsWith("/") || value.endsWith("/")
            || value.contains("..") || value.contains("@{") || value.contains("//")
            || value.codePoints().anyMatch(code -> Character.isISOControl(code)
                || " ~^:?*[]\\".indexOf(code) >= 0)) {
            throw new InvalidGovernanceInputException("Target branch is invalid");
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    public static String idempotencyKey(String value) {
        if (value == null || value.length() < 1 || value.length() > 128
            || value.codePoints().anyMatch(code -> code < 33 || code > 126)) {
            throw new InvalidGovernanceInputException("Idempotency-Key must be 1–128 printable ASCII characters");
        }
        return value;
    }
}
