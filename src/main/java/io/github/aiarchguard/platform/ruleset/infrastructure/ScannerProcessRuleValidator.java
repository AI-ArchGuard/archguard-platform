package io.github.aiarchguard.platform.ruleset.infrastructure;

import io.github.aiarchguard.platform.ruleset.InvalidRuleSetException;
import io.github.aiarchguard.platform.ruleset.internal.RuleValidationPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 规则校验端口的进程适配器：将 YAML 原文写入临时文件，通过 Scanner 公共 CLI 验证。
 * 这里只提供环境变量名过滤、有限进程等待和有限输出读取，不构成完整的进程沙箱或清理保证。
 */
@Component
final class ScannerProcessRuleValidator implements RuleValidationPort {
    private final Path scannerJar;
    private final Duration timeout;

    /** 固定校验器 JAR 的规范化绝对路径，并接收进程等待时限；不从上传的 YAML 决定可执行目标。 */
    ScannerProcessRuleValidator(@Value("${archguard.scanner.jar:/opt/archguard/scanner.jar}") String scannerJar,
                                @Value("${archguard.scanner.validation-timeout:10s}") Duration timeout) {
        this.scannerJar = Path.of(scannerJar).toAbsolutePath().normalize();
        this.timeout = timeout;
    }

    /**
     * 执行 validate-rules，退出码为 0 才接受规则；非零退出、启动故障、等待超时或中断均转换为校验异常。
     * 无论校验结果如何都会尝试清理临时文件，但此方法没有失败重试或后台回收实现。
     */
    @Override
    public void validate(String yaml) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("archguard-rules-");
            Path rules = directory.resolve("rules.yaml");
            Files.writeString(rules, yaml, StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("java", "-jar", scannerJar.toString(), "validate-rules", rules.toString());
            builder.redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            // 按大小写敏感的名称规则移除下列变量；其余父进程环境仍被继承，这不是完整白名单。
            environment.keySet().removeIf(key -> key.startsWith("ARCHGUARD_DB_") || key.startsWith("ARCHGUARD_OIDC_")
                || key.contains("TOKEN") || key.contains("SECRET") || key.contains("PASSWORD"));
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                // 只向此 Process 请求强制终止；没有等待终止确认，也没有遍历后代进程，不能保证整棵进程树回收。
                process.destroyForcibly();
                throw new InvalidRuleSetException("Scanner rule validation timed out");
            }
            // 退出后最多读取 4096 字节，只替换规则文件和 Scanner JAR 两个已知路径，并非任意秘密脱敏。
            String output = new String(process.getInputStream().readNBytes(4096), StandardCharsets.UTF_8)
                .replace(rules.toString(), "<rules.yaml>").replace(scannerJar.toString(), "<scanner.jar>").trim();
            if (process.exitValue() != 0) {
                throw new InvalidRuleSetException(output.isBlank() ? "Scanner rejected the rule configuration" : output);
            }
        } catch (IOException exception) {
            throw new InvalidRuleSetException("Scanner rule validation is unavailable");
        } catch (InterruptedException exception) {
            // 恢复线程中断标记，让上层保留取消信号；此分支本身没有终止已启动的子进程。
            Thread.currentThread().interrupt();
            throw new InvalidRuleSetException("Scanner rule validation was interrupted");
        } finally {
            if (directory != null) {
                try {
                    Files.deleteIfExists(directory.resolve("rules.yaml"));
                    Files.deleteIfExists(directory);
                } catch (IOException ignored) {
                    // 尽力清理，失败时忽略且不输出文件内容；当前实现没有清理重试或后台回收保证。
                }
            }
        }
    }
}
