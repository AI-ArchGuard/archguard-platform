package io.github.aiarchguard.platform.agentcredential;

/** 凭据模块的安全失败分类，只携带固定错误码，不附带 Key、文件路径或底层 cause。 */
public final class CredentialFailure extends RuntimeException {
    public enum Kind { INVALID, DENIED, UNAVAILABLE }
    private final Kind kind;
    public CredentialFailure(Kind kind) { super("credential." + kind.name().toLowerCase(java.util.Locale.ROOT)); this.kind = kind; }
    public Kind kind() { return kind; }
}
