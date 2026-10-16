package io.github.aiarchguard.platform.agentcredential.internal;

import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only files freshly created here; never locates or reads the user's runtime secrets. */
public final class SyntheticRuntimeCredentialFiles {
    private SyntheticRuntimeCredentialFiles() { }
    public static Paths create() {
        try {
            Path root = Files.createTempDirectory("archguard-personal-runtime-test-");
            Path directory = Files.createDirectory(root.resolve("encrypted")); PrivateCredentialFiles.restrict(directory, true);
            Path master = Files.write(root.resolve("synthetic-master"), new byte[32]); PrivateCredentialFiles.restrict(master, false);
            return new Paths(directory, master);
        } catch (Exception unavailable) { throw new IllegalStateException("Synthetic credential files unavailable", unavailable); }
    }
    public record Paths(Path directory, Path master) { }
}
