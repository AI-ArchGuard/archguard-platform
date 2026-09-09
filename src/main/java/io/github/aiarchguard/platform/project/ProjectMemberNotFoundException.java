package io.github.aiarchguard.platform.project;

public final class ProjectMemberNotFoundException extends RuntimeException {
    public ProjectMemberNotFoundException() {
        super("Project member was not found");
    }
}
