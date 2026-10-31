package io.github.aiarchguard.platform.agent.internal;

/** RED seam for the privately fixed canonical typed-snapshot encoding. */
public final class DailySnapshotHashes {
    private DailySnapshotHashes() {}
    public static byte[] canonicalBytes(Object snapshot) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    public static String sha256(Object snapshot) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
}
