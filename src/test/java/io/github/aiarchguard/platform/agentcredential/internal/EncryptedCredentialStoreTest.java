package io.github.aiarchguard.platform.agentcredential.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedCredentialStoreTest {
    @TempDir Path root;
    Path directory;
    Path master;
    final UUID deployment = UUID.randomUUID();
    final String synthetic = "synthetic-test-credential-not-a-real-api-key";
    final Instant now = Instant.parse("2026-10-06T00:00:00Z");

    @BeforeEach void setup() throws Exception {
        directory = Files.createDirectory(root.resolve("encrypted"));
        PrivateCredentialFiles.restrict(directory, true);
        master = Files.write(root.resolve("master"), new byte[32]);
        PrivateCredentialFiles.restrict(master, false);
    }

    EncryptedCredentialStore store() { return new EncryptedCredentialStore(directory, master, deployment); }

    @Test void survivesRestartWithoutPlaintextOrReadback() throws Exception {
        assertThat(store().status().configured()).isFalse();
        var saved = store().write(synthetic, now);
        assertThat(saved.configured()).isTrue();
        assertThat(saved.updatedAt()).isEqualTo(now);
        assertThat(Files.readString(directory.resolve("deepseek.credential"), java.nio.charset.StandardCharsets.ISO_8859_1))
            .doesNotContain(synthetic);
        assertThat(store().status()).isEqualTo(saved);
        assertThat(saved.toString()).doesNotContain(synthetic);
    }

    @Test void replacesVersionAndDeletesIdempotently() {
        var first = store().write(synthetic, now);
        var replacement = store().write(synthetic + "-replacement", now.plusSeconds(1));
        assertThat(replacement.credentialVersion()).isNotEqualTo(first.credentialVersion());
        assertThat(store().delete().configured()).isFalse();
        assertThat(store().delete().configured()).isFalse();
        assertThat(store().status().configured()).isFalse();
    }

    @Test void wrongMasterTamperingAndDeploymentFailClosedWithoutReset() throws Exception {
        store().write(synthetic, now);
        byte[] encrypted = Files.readAllBytes(directory.resolve("deepseek.credential"));
        Files.write(master, new byte[32]);
        byte[] changedMaster = new byte[32]; changedMaster[0] = 1; Files.write(master, changedMaster);
        assertThatThrownBy(() -> store().status()).isInstanceOf(CredentialFailure.class).hasMessage("credential.unavailable");
        assertThatThrownBy(() -> store().write(synthetic, now)).isInstanceOf(CredentialFailure.class);
        assertThatThrownBy(() -> store().delete()).isInstanceOf(CredentialFailure.class);
        assertThat(Files.readAllBytes(directory.resolve("deepseek.credential"))).isEqualTo(encrypted);
        Files.write(master, new byte[32]);
        assertThatThrownBy(() -> new EncryptedCredentialStore(directory, master, UUID.randomUUID()).status())
            .isInstanceOf(CredentialFailure.class);
        encrypted[encrypted.length - 1] ^= 1; Files.write(directory.resolve("deepseek.credential"), encrypted);
        assertThatThrownBy(() -> store().status()).isInstanceOf(CredentialFailure.class);
    }

    @Test void rejectsMissingWrongSizeAndCoLocatedMaster() throws Exception {
        Files.delete(master);
        assertThatThrownBy(() -> store().status()).isInstanceOf(CredentialFailure.class);
        Files.write(master, new byte[33]); PrivateCredentialFiles.restrict(master, false);
        assertThatThrownBy(() -> store().status()).isInstanceOf(CredentialFailure.class);
        Path coLocated = Files.write(directory.resolve("master"), new byte[32]);
        PrivateCredentialFiles.restrict(coLocated, false);
        assertThatThrownBy(() -> new EncryptedCredentialStore(directory, coLocated, deployment).status())
            .isInstanceOf(CredentialFailure.class);
    }

    @Test void invalidCredentialsNeverCreateCiphertextAndErrorsAreSanitized() {
        for (String bad : new String[]{"", synthetic + "\r\nInjected: yes", "a".repeat(257)}) {
            assertThatThrownBy(() -> store().write(bad, now)).isInstanceOf(CredentialFailure.class)
                .hasMessage("credential.invalid").hasNoCause();
        }
        assertThat(Files.exists(directory.resolve("deepseek.credential"))).isFalse();
    }

    @Test void boundedCorruptFileIsNotOverwrittenOrDeleted() throws Exception {
        Path file = Files.write(directory.resolve("deepseek.credential"), new byte[513]);
        PrivateCredentialFiles.restrict(file, false);
        assertThatThrownBy(() -> store().status()).isInstanceOf(CredentialFailure.class).hasNoCause();
        assertThatThrownBy(() -> store().delete()).isInstanceOf(CredentialFailure.class);
        assertThat(Files.size(file)).isEqualTo(513);
    }

    @Test void concurrentOperationsOnSingleProcessStoreRemainAuthenticated() throws Exception {
        var shared = store();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var calls = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> executor.submit(() -> shared.write(synthetic + i, now.plusSeconds(i))))
                .toList();
            for (var call : calls) assertThat(call.get().configured()).isTrue();
        }
        assertThat(shared.status().configured()).isTrue();
        assertThat(store().status()).isEqualTo(shared.status());
    }

    @Test void repeatedWritesUseDistinctAuthenticatedCiphertexts() throws Exception {
        store().write(synthetic, now);
        byte[] first = Files.readAllBytes(directory.resolve("deepseek.credential"));
        store().write(synthetic, now);
        byte[] second = Files.readAllBytes(directory.resolve("deepseek.credential"));
        assertThat(second).isNotEqualTo(first);
        assertThat(java.util.Arrays.copyOfRange(second, 4, 16)).isNotEqualTo(java.util.Arrays.copyOfRange(first, 4, 16));
    }

    @Test void permissiveSecretPermissionsFailClosed() throws Exception {
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
        assertThatThrownBy(() -> store().status()).isInstanceOf(CredentialFailure.class).hasNoCause();
    }

    @Test void nonNormalizedPathsCannotSelectCredentialFiles() {
        assertThatThrownBy(() -> new EncryptedCredentialStore(directory.resolve(".."), master, deployment).status())
            .isInstanceOf(CredentialFailure.class);
        assertThatThrownBy(() -> new EncryptedCredentialStore(directory, root.resolve("encrypted/../master"), deployment).status())
            .isInstanceOf(CredentialFailure.class);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void symlinksCannotSelectMasterOrCiphertext() throws Exception {
        Path linkedMaster = Files.createSymbolicLink(root.resolve("linked-master"), master);
        assertThatThrownBy(() -> new EncryptedCredentialStore(directory, linkedMaster, deployment).status())
            .isInstanceOf(CredentialFailure.class);
        Files.createSymbolicLink(directory.resolve("deepseek.credential"), master);
        assertThatThrownBy(() -> store().status()).isInstanceOf(CredentialFailure.class);
        assertThatThrownBy(() -> store().delete()).isInstanceOf(CredentialFailure.class);
        assertThat(Files.isSymbolicLink(directory.resolve("deepseek.credential"))).isTrue();
    }
}
