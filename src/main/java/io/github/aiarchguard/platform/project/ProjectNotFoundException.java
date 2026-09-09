package io.github.aiarchguard.platform.project;

public final class ProjectNotFoundException extends RuntimeException {
    public ProjectNotFoundException() {
        super("Project was not found");
    }
}
