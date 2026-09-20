package io.github.aiarchguard.platform.identity;

public final class InvalidCurrentActorException extends RuntimeException {
    public InvalidCurrentActorException() {
        super("The authenticated actor identifier is invalid");
    }
}
