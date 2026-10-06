package io.github.aiarchguard.platform.agentcredential;

public final class CredentialFailure extends RuntimeException {
    public enum Kind { INVALID, DENIED, UNAVAILABLE }
    private final Kind kind;
    public CredentialFailure(Kind kind) { super("credential." + kind.name().toLowerCase(java.util.Locale.ROOT)); this.kind = kind; }
    public Kind kind() { return kind; }
}
