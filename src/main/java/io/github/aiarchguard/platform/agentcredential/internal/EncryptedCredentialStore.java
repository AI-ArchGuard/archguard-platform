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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 单进程固定部署槽位：密文认证、版本消费、替换和删除共用同一锁，不支持多实例共享。 */
final class EncryptedCredentialStore {
    private static final int MAGIC = 0x41474301;
    private final Path directory;
    private final Path master;
    private final byte[] aad;
    private final SecureRandom random = new SecureRandom();
    private final ReentrantLock lock = new ReentrantLock();
    private boolean consuming;

    EncryptedCredentialStore(Path directory, Path master, UUID deployment) {
        this.directory = directory; this.master = master;
        this.aad = ("ArchGuard|personal|DeepSeek|credential-v1|" + deployment).getBytes(StandardCharsets.US_ASCII);
    }

    /** 即使只读元数据也认证当前密文；可擦除的主密钥字节在 finally 清零。 */
    CredentialStatus status() {
        return locked(() -> {
            byte[] key = null;
            try { key = master(); return current(key); }
            catch (Exception failure) { throw PrivateCredentialFiles.unavailable(); }
            finally { wipe(key); }
        });
    }

    /** 有界取同一槽位锁，并在文件/解密操作返回后复核期限，拒绝迟到状态。 */
    CredentialStatus status(LongSupplier remainingNanos) {
        if (lock.isHeldByCurrentThread()) rejectReentry();
        var deadline = new CredentialDeadline(remainingNanos);
        boolean acquired = false;
        byte[] key = null;
        try {
            acquired = lock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) throw PrivateCredentialFiles.unavailable();
            rejectReentry(); deadline.remainingNanos();
            key = master(); deadline.remainingNanos();
            var status = withCurrent(key, deadline, (metadata, apiKey) -> metadata);
            deadline.remainingNanos();
            return status;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw PrivateCredentialFiles.unavailable();
        } catch (Exception unavailable) { throw PrivateCredentialFiles.unavailable(); }
        finally { wipe(key); if (acquired) lock.unlock(); }
    }

    CredentialStatus write(String apiKey, Instant now) {
        return locked(() -> writeLocked(apiKey, now));
    }

    /** 先认证旧密文，再用随机 nonce 和新版本加密；临时文件强制落盘后只允许原子替换。 */
    private CredentialStatus writeLocked(String apiKey, Instant now) {
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
            // 不支持原子移动时直接失败，不降级为可能留下半写入文件的普通替换。
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

    /** 删除前仍验证主密钥与旧密文；不重置损坏数据，也不保证磁盘或快照物理擦除。 */
    CredentialStatus delete() {
        return locked(() -> {
            byte[] key = null;
            try { key = master(); current(key); Files.deleteIfExists(file()); return CredentialStatus.absent(); }
            catch (Exception failure) { throw PrivateCredentialFiles.unavailable(); }
            finally { wipe(key); }
        });
    }

    void consume(UUID expectedVersion, Consumer<String> finalOperation) {
        consumeWithDeadline(expectedVersion, null, finalOperation);
    }

    void consume(UUID expectedVersion, LongSupplier remainingNanos, Consumer<String> finalOperation) {
        if (lock.isHeldByCurrentThread()) rejectReentry();
        if (expectedVersion == null || finalOperation == null) throw PrivateCredentialFiles.unavailable();
        consumeWithDeadline(expectedVersion, new CredentialDeadline(remainingNanos), finalOperation);
    }

    /** 锁内认证并匹配当前版本后同步回调；锁等待可中断，有界路径沿用原预算。 */
    private void consumeWithDeadline(UUID expectedVersion, CredentialDeadline deadline, Consumer<String> finalOperation) {
        if (expectedVersion == null || finalOperation == null) throw PrivateCredentialFiles.unavailable();
        byte[] key = null;
        boolean acquired = false;
        try {
            if (deadline == null) {
                lock.lockInterruptibly(); acquired = true;
            } else {
                acquired = lock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
                if (!acquired) throw PrivateCredentialFiles.unavailable();
            }
            rejectReentry(); check(deadline);
            key = master();
            check(deadline);
            withCurrent(key, deadline, (status, apiKey) -> {
                check(deadline);
                if (!status.configured() || !expectedVersion.equals(status.credentialVersion())) {
                    throw PrivateCredentialFiles.unavailable();
                }
                // 已开始的回调与替换/删除串行；后者不能撤回回调已经产生的外部副作用。
                consuming = true;
                try { check(deadline); finalOperation.accept(apiKey); check(deadline); }
                finally { consuming = false; }
                return null;
            });
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt(); throw PrivateCredentialFiles.unavailable();
        } catch (Exception failure) { throw PrivateCredentialFiles.unavailable(); }
        finally { wipe(key); if (acquired) lock.unlock(); }
    }

    /** 管理操作仍无期限；同线程回调重入会被 consuming 标志拒绝。 */
    private <T> T locked(Supplier<T> operation) {
        lock.lock();
        try { rejectReentry(); return operation.get(); }
        finally { lock.unlock(); }
    }

    private static void check(CredentialDeadline deadline) {
        if (Thread.currentThread().isInterrupted()) throw PrivateCredentialFiles.unavailable();
        if (deadline != null) deadline.remainingNanos();
    }

    private void rejectReentry() {
        if (consuming) throw PrivateCredentialFiles.unavailable();
    }

    /** 要求受控绝对目录、目录外独立主密钥和恰好 32 个原始字节，不自动修复权限。 */
    private byte[] master() throws Exception {
        PrivateCredentialFiles.check(directory, true);
        PrivateCredentialFiles.check(master, false);
        if (master.startsWith(directory)) throw PrivateCredentialFiles.unavailable();
        return bounded(master, 32, 32);
    }

    private CredentialStatus current(byte[] key) throws Exception {
        return withCurrent(key, (status, apiKey) -> status);
    }

    private <T> T withCurrent(byte[] key, BiFunction<CredentialStatus, String, T> operation) throws Exception {
        return withCurrent(key, null, operation);
    }

    /** 有界读取并认证封装及部署 AAD，校验内部元数据后才允许使用明文。 */
    private <T> T withCurrent(byte[] key, CredentialDeadline deadline, BiFunction<CredentialStatus, String, T> operation) throws Exception {
        if (Files.notExists(file(), LinkOption.NOFOLLOW_LINKS)) return operation.apply(CredentialStatus.absent(), null);
        PrivateCredentialFiles.check(file(), false);
        byte[] envelope = bounded(file(), 33, 512); byte[] plain = null;
        try {
            if (deadline != null) deadline.remainingNanos();
            var bytes = ByteBuffer.wrap(envelope);
            if (bytes.getInt() != MAGIC) throw PrivateCredentialFiles.unavailable();
            byte[] nonce = new byte[12]; bytes.get(nonce);
            byte[] encrypted = new byte[bytes.remaining()]; bytes.get(encrypted);
            plain = cipher(Cipher.DECRYPT_MODE, key, nonce).doFinal(encrypted);
            String[] values = new String(plain, StandardCharsets.UTF_8).split("\n", -1);
            if (values.length != 3 || !valid(values[2])) throw PrivateCredentialFiles.unavailable();
            return operation.apply(new CredentialStatus(true, UUID.fromString(values[0]), Instant.parse(values[1])), values[2]);
        } finally { wipe(plain); }
    }

    /** AES-GCM 同时保护正文与部署/提供方/格式绑定，错主密钥或篡改均不能通过认证。 */
    private Cipher cipher(int mode, byte[] key, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher;
    }

    /** 最多多读一字节检测超限，不跟随符号链接，错误不附带路径或正文。 */
    private byte[] bounded(Path path, int min, int max) throws Exception {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] result = input.readNBytes(max + 1);
            if (result.length < min || result.length > max) { wipe(result); throw PrivateCredentialFiles.unavailable(); }
            return result;
        }
    }

    private Path file() { return directory.resolve("deepseek.credential"); }
    private static boolean valid(String value) { return value != null && value.matches("[A-Za-z0-9_-]{16,256}"); }
    // 只能清理持有的 byte[]；JVM String 和加密/传输提供者内部拷贝不保证物理擦除。
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
}
