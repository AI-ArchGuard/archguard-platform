package io.github.aiarchguard.platform.governance;

public class InvalidGithubWebhookException extends RuntimeException {
    public InvalidGithubWebhookException(String message) { super(message); }
}
