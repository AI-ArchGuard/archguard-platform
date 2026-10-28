package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 使用临时文件验证运行时 Secret 的有界读取与字符校验。只处理合成凭据，不读取真实部署 Secret 或探测 Key 有效性。
 */
class DeepSeekRuntimeSecretTest {
    @TempDir Path directory;

    // 显式 Secret 文件才是来源，不能回退到环境默认值。
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

    // 拒绝请求头注入、空值和超限内容，错误不回显路径或正文。
    @Test void rejectsHeaderInjectionWhitespaceEmptyAndLargeFilesWithoutLeakingPathOrContent() throws Exception {
        Path key = directory.resolve("synthetic-private-path");
        for (String value : new String[] {"", "short", " synthetic-test-key", "synthetic-test-key\nother-header", "x".repeat(1024)}) {
            Files.writeString(key, value);
            assertThatThrownBy(() -> DeepSeekRuntimeSecret.read(key)).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        }
    }
}
