package io.github.aiarchguard.platform.agentcredential.internal;

import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** One process, one fixed deployment slot. No runtime model port consumes this store yet. */
final class EncryptedCredentialStore {
    private static final int MAGIC = 0x41474301;
    private final Path directory;
    private final Path master;
    private final byte[] aad;
    private final SecureRandom random = new SecureRandom();

    EncryptedCredentialStore(Path directory, Path master, UUID deployment) {
        this.directory = directory; this.master = master;
        this.aad = ("ArchGuard|personal|DeepSeek|credential-v1|" + deployment).getBytes(StandardCharsets.US_ASCII);
    }

    synchronized CredentialStatus status() {
        byte[] key = null;
        try { key = master(); return current(key); }
        catch (Exception failure) { throw PrivateCredentialFiles.unavailable(); }
        finally { wipe(key); }
    }

    synchronized CredentialStatus write(String apiKey, Instant now) {
        if (!valid(apiKey)) throw new CredentialFailure(CredentialFailure.Kind.INVALID);
        byte[] key = null; byte[] plain = null; Path pending = null;
        try {
            key = master(); current(key); // Never silently overwrite corrupted data or a wrong master.
            UUID version = UUID.randomUUID();
            plain = (version + "\n" + now + "\n" + apiKey).getBytes(StandardCharsets.UTF_8);
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce);
            byte[] encrypted = cipher.doFinal(plain);
            byte[] envelope = ByteBuffer.allocate(4 + nonce.length + encrypted.length)
                .putInt(MAGIC).put(nonce).put(encrypted).array();
            pending = Files.createTempFile(directory, ".pending-", ".encrypted", PrivateCredentialFiles.privateAttribute(directory));
            try (var channel = java.nio.channels.FileChannel.open(pending, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                var bytes = ByteBuffer.wrap(envelope);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(pending, file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            pending = null;
            return new CredentialStatus(true, version, now);
        } catch (Exception failure) { throw PrivateCredentialFiles.unavailable(); }
        finally {
            wipe(key); wipe(plain);
            if (pending != null) {
                try { Files.deleteIfExists(pending); }
                catch (Exception cleanupFailure) { throw PrivateCredentialFiles.unavailable(); }
            }
        }
    }

    synchronized CredentialStatus delete() {
        byte[] key = null;
        try { key = master(); current(key); Files.deleteIfExists(file()); return CredentialStatus.absent(); }
        catch (Exception failure) { throw PrivateCredentialFiles.unavailable(); }
        finally { wipe(key); }
    }

    private byte[] master() throws Exception {
        PrivateCredentialFiles.check(directory, true);
        PrivateCredentialFiles.check(master, false);
        if (master.startsWith(directory)) throw PrivateCredentialFiles.unavailable();
        return bounded(master, 32, 32);
    }

    private CredentialStatus current(byte[] key) throws Exception {
        if (Files.notExists(file(), LinkOption.NOFOLLOW_LINKS)) return CredentialStatus.absent();
        PrivateCredentialFiles.check(file(), false);
        byte[] envelope = bounded(file(), 33, 512); byte[] plain = null;
        try {
            var bytes = ByteBuffer.wrap(envelope);
            if (bytes.getInt() != MAGIC) throw PrivateCredentialFiles.unavailable();
            byte[] nonce = new byte[12]; bytes.get(nonce);
            byte[] encrypted = new byte[bytes.remaining()]; bytes.get(encrypted);
            plain = cipher(Cipher.DECRYPT_MODE, key, nonce).doFinal(encrypted);
            String[] values = new String(plain, StandardCharsets.UTF_8).split("\n", -1);
            if (values.length != 3 || !valid(values[2])) throw PrivateCredentialFiles.unavailable();
            return new CredentialStatus(true, UUID.fromString(values[0]), Instant.parse(values[1]));
        } finally { wipe(plain); }
    }

    private Cipher cipher(int mode, byte[] key, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher;
    }

    private byte[] bounded(Path path, int min, int max) throws Exception {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] result = input.readNBytes(max + 1);
            if (result.length < min || result.length > max) { wipe(result); throw PrivateCredentialFiles.unavailable(); }
            return result;
        }
    }

    private Path file() { return directory.resolve("deepseek.credential"); }
    private static boolean valid(String value) { return value != null && value.matches("[A-Za-z0-9_-]{16,256}"); }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
}
