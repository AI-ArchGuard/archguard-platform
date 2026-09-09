package io.github.aiarchguard.platform.project;

public final class ProjectPermissionDeniedException extends RuntimeException {
    public ProjectPermissionDeniedException() {
        super("Project operation is not permitted");
    }
}
