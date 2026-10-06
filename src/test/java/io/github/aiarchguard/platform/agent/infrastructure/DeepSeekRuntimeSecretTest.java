package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeepSeekRuntimeSecretTest {
    @TempDir Path directory;

    @Test void readsOnlyBoundedRuntimeSecretFilesNotEnvironmentDefaults() throws Exception {
        Path key = directory.resolve("synthetic-key");
        for (String suffix : new String[] {"", "\n", "\r\n"}) {
            Files.writeString(key, "synthetic-test-key" + suffix);
            assertThat(DeepSeekRuntimeSecret.read(key)).isEqualTo("synthetic-test-key");
        }
        assertThatThrownBy(() -> DeepSeekRuntimeSecret.read(Path.of("synthetic-key")))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThatThrownBy(() -> DeepSeekRuntimeSecret.read(directory)).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThatThrownBy(() -> DeepSeekRuntimeSecret.read(directory.resolve("missing")))
            .hasMessage("MODEL_UNAVAILABLE").hasNoCause();
    }

    @Test void rejectsHeaderInjectionWhitespaceEmptyAndLargeFilesWithoutLeakingPathOrContent() throws Exception {
        Path key = directory.resolve("synthetic-private-path");
        for (String value : new String[] {"", "short", " synthetic-test-key", "synthetic-test-key\nother-header", "x".repeat(1024)}) {
            Files.writeString(key, value);
            assertThatThrownBy(() -> DeepSeekRuntimeSecret.read(key)).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        }
    }
}
