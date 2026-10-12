package io.github.aiarchguard.platform.agentcredential.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedCredentialConsumptionTest {
    @TempDir Path root;
    Path directory, master;
    final UUID deployment = UUID.randomUUID();
    final String synthetic = "synthetic-test-credential-not-a-real-api-key";
    final Instant now = Instant.parse("2026-10-12T00:00:00Z");

    @BeforeEach void setup() throws Exception {
        directory = Files.createDirectory(root.resolve("encrypted"));
        PrivateCredentialFiles.restrict(directory, true);
        master = Files.write(root.resolve("master"), new byte[32]);
        PrivateCredentialFiles.restrict(master, false);
    }
    EncryptedCredentialStore store() { return new EncryptedCredentialStore(directory, master, deployment); }

    @Test void currentVersionConsumedOnceAfterRestartWithoutCiphertextMutation() throws Exception {
        var saved = store().write(synthetic, now);
        byte[] encrypted = Files.readAllBytes(directory.resolve("deepseek.credential"));
        var restarted = store();
        var calls = new AtomicInteger();
        restarted.consume(saved.credentialVersion(), key -> {
            assertThat(key).isEqualTo(synthetic); calls.incrementAndGet();
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(restarted.status()).isEqualTo(saved);
        assertThat(Files.readAllBytes(directory.resolve("deepseek.credential"))).isEqualTo(encrypted);
    }

    @Test void missingNullStaleRotatedAndDeletedVersionsNeverInvokeCallback() {
        var shared = store();
        var calls = new AtomicInteger();
        java.util.function.Consumer<String> callback = key -> calls.incrementAndGet();
        assertUnavailable(() -> shared.consume(UUID.randomUUID(), callback));
        var first = shared.write(synthetic, now);
        assertUnavailable(() -> shared.consume(null, callback));
        assertUnavailable(() -> shared.consume(first.credentialVersion(), null));
        assertUnavailable(() -> shared.consume(UUID.randomUUID(), callback));
        var second = shared.write(synthetic + "-replacement", now.plusSeconds(1));
        assertUnavailable(() -> shared.consume(first.credentialVersion(), callback));
        shared.delete();
        assertUnavailable(() -> shared.consume(second.credentialVersion(), callback));
        assertThat(calls.get()).isZero();
    }

    @Test void authenticationAndEnvelopeFailuresNeverInvokeCallbackOrResetCiphertext() throws Exception {
        var saved = store().write(synthetic, now);
        byte[] encrypted = Files.readAllBytes(directory.resolve("deepseek.credential"));
        var calls = new AtomicInteger();
        java.util.function.Consumer<String> callback = key -> calls.incrementAndGet();
        byte[] badMaster = new byte[32]; badMaster[0] = 1; Files.write(master, badMaster);
        assertUnavailable(() -> store().consume(saved.credentialVersion(), callback));
        assertThat(Files.readAllBytes(directory.resolve("deepseek.credential"))).isEqualTo(encrypted);
        Files.write(master, new byte[32]);
        assertUnavailable(() -> new EncryptedCredentialStore(directory, master, UUID.randomUUID())
            .consume(saved.credentialVersion(), callback));
        encrypted[encrypted.length - 1] ^= 1; Files.write(directory.resolve("deepseek.credential"), encrypted);
        assertUnavailable(() -> store().consume(saved.credentialVersion(), callback));
        assertThat(Files.readAllBytes(directory.resolve("deepseek.credential"))).isEqualTo(encrypted);
        Files.write(directory.resolve("deepseek.credential"), new byte[513]);
        assertUnavailable(() -> store().consume(saved.credentialVersion(), callback));
        assertThat(Files.size(directory.resolve("deepseek.credential"))).isEqualTo(513);
        assertThat(calls.get()).isZero();
    }

    @Test void callbackExceptionsAreSanitizedAndReleaseConsumptionGuard() {
        var shared = store(); var saved = shared.write(synthetic, now);
        assertUnavailable(() -> shared.consume(saved.credentialVersion(), key -> {
            throw new IllegalStateException(key + master, new RuntimeException(key));
        }));
        assertThat(shared.status()).isEqualTo(saved);
        shared.consume(saved.credentialVersion(), key -> assertThat(key).isEqualTo(synthetic));
        assertThat(shared.delete().configured()).isFalse();
    }

    @Test void unsafePermissionsNeverPermitConsumption() throws Exception {
        var saved = store().write(synthetic, now); var calls = new AtomicInteger();
        if (Files.getFileAttributeView(master, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(master, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        } else {
            var view = Files.getFileAttributeView(master, java.nio.file.attribute.AclFileAttributeView.class);
            var everyone = master.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
            var entries = new java.util.ArrayList<>(view.getAcl());
            entries.add(java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPrincipal(everyone).setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA).build());
            view.setAcl(entries);
        }
        assertUnavailable(() -> store().consume(saved.credentialVersion(), key -> calls.incrementAndGet()));
        assertThat(calls.get()).isZero();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void symlinkMasterOrCiphertextNeverPermitsConsumption() throws Exception {
        var saved = store().write(synthetic, now); var calls = new AtomicInteger();
        Path linked = Files.createSymbolicLink(root.resolve("linked-master"), master);
        assertUnavailable(() -> new EncryptedCredentialStore(directory, linked, deployment)
            .consume(saved.credentialVersion(), key -> calls.incrementAndGet()));
        Path encrypted = directory.resolve("deepseek.credential");
        Path original = root.resolve("original-credential"); Files.move(encrypted, original);
        Files.createSymbolicLink(encrypted, original);
        assertUnavailable(() -> store().consume(saved.credentialVersion(), key -> calls.incrementAndGet()));
        assertThat(Files.isSymbolicLink(encrypted)).isTrue();
        assertThat(calls.get()).isZero();
    }

    @Test void reentrantStatusReplacementDeletionAndConsumptionAreRejectedWithoutMutation() throws Exception {
        var shared = store(); var saved = shared.write(synthetic, now);
        byte[] encrypted = Files.readAllBytes(directory.resolve("deepseek.credential"));
        var nestedCalls = new AtomicInteger();
        shared.consume(saved.credentialVersion(), key -> {
            assertUnavailable(shared::status);
            assertUnavailable(() -> shared.write(synthetic + "-replacement", now.plusSeconds(1)));
            assertUnavailable(shared::delete);
            assertUnavailable(() -> shared.consume(saved.credentialVersion(), ignored -> nestedCalls.incrementAndGet()));
        });
        assertThat(nestedCalls.get()).isZero();
        assertThat(shared.status()).isEqualTo(saved);
        assertThat(Files.readAllBytes(directory.resolve("deepseek.credential"))).isEqualTo(encrypted);
    }

    @Test void replacementWaitsForStartedCallbackAndOldVersionCannotStartAfterward() throws Exception {
        serializesMutation(false);
    }
    @Test void deletionWaitsForStartedCallbackAndOldVersionCannotStartAfterward() throws Exception {
        serializesMutation(true);
    }

    private void serializesMutation(boolean delete) throws Exception {
        var shared = store(); var saved = shared.write(synthetic, now);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var mutationStarted = new CountDownLatch(1); var calls = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var active = executor.submit(() -> shared.consume(saved.credentialVersion(), key -> {
                    assertThat(key).isEqualTo(synthetic); calls.incrementAndGet(); entered.countDown();
                    try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                }));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var mutation = executor.submit(() -> {
                    mutationStarted.countDown();
                    return delete ? shared.delete() : shared.write(synthetic + "-replacement", now.plusSeconds(1));
                });
                assertThat(mutationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> mutation.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); active.get(5, TimeUnit.SECONDS);
                assertThat(mutation.get(5, TimeUnit.SECONDS).configured()).isEqualTo(!delete);
                assertUnavailable(() -> shared.consume(saved.credentialVersion(), key -> calls.incrementAndGet()));
                assertThat(calls.get()).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(CredentialFailure.class).hasMessage("credential.unavailable").hasNoCause();
    }
}
