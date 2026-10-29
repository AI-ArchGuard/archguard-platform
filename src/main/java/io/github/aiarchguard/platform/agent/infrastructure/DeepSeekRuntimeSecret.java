package io.github.aiarchguard.platform.agent.infrastructure;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 未注册且无默认路径的运行时 Secret 文件读取工具。仅检查规范常规文件和有界 Key 形状；
 * 挂载 ACL 仍须调用者确认，字节清理不等于可清除 Java String。
 */
final class DeepSeekRuntimeSecret {
    private DeepSeekRuntimeSecret() { }

    /** 从调用者指定的规范文件有界读取，最多去掉一个末尾换行再校验 Key 形状；错误不暴露路径、内容或底层 cause。 */
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
