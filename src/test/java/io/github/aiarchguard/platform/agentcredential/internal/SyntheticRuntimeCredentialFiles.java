package io.github.aiarchguard.platform.agentcredential.internal;

import java.nio.file.Files;
import java.nio.file.Path;

/** 只创建全新的测试临时文件，绝不定位或读取用户运行时秘密。 */
public final class SyntheticRuntimeCredentialFiles {
    private SyntheticRuntimeCredentialFiles() { }
    /** 为合成运行时准备私有目录和目录外假主密钥；失败直接终止测试设置。 */
    public static Paths create() {
        try {
            Path root = Files.createTempDirectory("archguard-personal-runtime-test-");
            Path directory = Files.createDirectory(root.resolve("encrypted")); PrivateCredentialFiles.restrict(directory, true);
            Path master = Files.write(root.resolve("synthetic-master"), new byte[32]); PrivateCredentialFiles.restrict(master, false);
            return new Paths(directory, master);
        } catch (Exception unavailable) { throw new IllegalStateException("Synthetic credential files unavailable", unavailable); }
    }
    /** 仅供测试装配的临时目录与假主密钥路径。 */
    public record Paths(Path directory, Path master) { }
}
