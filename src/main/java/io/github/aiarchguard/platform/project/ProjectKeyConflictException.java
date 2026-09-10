package io.github.aiarchguard.platform.project;

public final class ProjectKeyConflictException extends RuntimeException {
    public ProjectKeyConflictException(String key) {
        super("Project key already exists: " + key);
    }
}
