package io.github.aiarchguard.platform.agent.infrastructure;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** No default path/configuration, no Spring bean. Caller must verify Secret mount ACLs before use. */
final class DeepSeekRuntimeSecret {
    private DeepSeekRuntimeSecret() { }

    static String read(Path path) {
        try {
            if (path == null || !path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !path.normalize().equals(path.toRealPath())) throw new DeepSeekTransportFailure("MODEL_UNAVAILABLE");
            byte[] bytes;
            try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes(259);
            }
            String key;
            try {
                if (bytes.length > 258) throw new DeepSeekTransportFailure("MODEL_UNAVAILABLE");
                key = new String(bytes, StandardCharsets.UTF_8);
            } finally { java.util.Arrays.fill(bytes, (byte) 0); }
            if (key.endsWith("\r\n")) key = key.substring(0, key.length() - 2);
            else if (key.endsWith("\n")) key = key.substring(0, key.length() - 1);
            if (!key.matches("[A-Za-z0-9._-]{16,256}")) throw new DeepSeekTransportFailure("MODEL_UNAVAILABLE");
            return key;
        } catch (Exception failure) {
            throw new DeepSeekTransportFailure("MODEL_UNAVAILABLE");
        }
    }
}
