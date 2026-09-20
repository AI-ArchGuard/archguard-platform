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

@Component
final class ScannerProcessRuleValidator implements RuleValidationPort {
    private final Path scannerJar;
    private final Duration timeout;

    ScannerProcessRuleValidator(@Value("${archguard.scanner.jar:/opt/archguard/scanner.jar}") String scannerJar,
                                @Value("${archguard.scanner.validation-timeout:10s}") Duration timeout) {
        this.scannerJar = Path.of(scannerJar).toAbsolutePath().normalize();
        this.timeout = timeout;
    }

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
            environment.keySet().removeIf(key -> key.startsWith("ARCHGUARD_DB_") || key.startsWith("ARCHGUARD_OIDC_")
                || key.contains("TOKEN") || key.contains("SECRET") || key.contains("PASSWORD"));
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new InvalidRuleSetException("Scanner rule validation timed out");
            }
            String output = new String(process.getInputStream().readNBytes(4096), StandardCharsets.UTF_8)
                .replace(rules.toString(), "<rules.yaml>").replace(scannerJar.toString(), "<scanner.jar>").trim();
            if (process.exitValue() != 0) {
                throw new InvalidRuleSetException(output.isBlank() ? "Scanner rejected the rule configuration" : output);
            }
        } catch (IOException exception) {
            throw new InvalidRuleSetException("Scanner rule validation is unavailable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InvalidRuleSetException("Scanner rule validation was interrupted");
        } finally {
            if (directory != null) {
                try {
                    Files.deleteIfExists(directory.resolve("rules.yaml"));
                    Files.deleteIfExists(directory);
                } catch (IOException ignored) {
                    // The task workspace janitor retries failed cleanup without exposing file content.
                }
            }
        }
    }
}
