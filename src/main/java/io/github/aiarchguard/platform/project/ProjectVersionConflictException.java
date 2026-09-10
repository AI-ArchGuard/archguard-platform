package io.github.aiarchguard.platform.project;

public final class ProjectVersionConflictException extends RuntimeException {
    public ProjectVersionConflictException() {
        super("The project was modified by another request");
    }
}
