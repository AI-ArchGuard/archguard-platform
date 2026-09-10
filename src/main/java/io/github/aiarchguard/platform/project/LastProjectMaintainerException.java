package io.github.aiarchguard.platform.project;

public final class LastProjectMaintainerException extends RuntimeException {
    public LastProjectMaintainerException() {
        super("A project must retain at least one maintainer");
    }
}
