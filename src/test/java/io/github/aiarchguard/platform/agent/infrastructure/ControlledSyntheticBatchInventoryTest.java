package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ControlledSyntheticBatchInventoryTest {
    private static final Instant NOW = Instant.parse("2026-10-10T08:00:00Z");
    private static final UUID DEPLOYMENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INVENTORY = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String DIGEST = "a".repeat(64);
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private MockEnvironment environment;
    private ControlledSyntheticBatchInventory provider;
    private Path directory, file;
    private UserPrincipal outsider;

    @BeforeEach void prepare() throws Exception {
        directory = Files.createDirectory(temporary.resolve("controlled"));
        var acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        if (acl != null) {
            var owner = acl.getOwner();
            outsider = acl.getAcl().stream().filter(entry -> !entry.principal().equals(owner)
                    && !entry.principal().getName().equalsIgnoreCase("NT AUTHORITY\\SYSTEM"))
                .map(AclEntry::principal).findFirst().orElseGet(() -> {
                    try { return directory.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone"); }
                    catch (java.io.IOException unavailable) { throw new IllegalStateException(unavailable); }
                });
        }
        restrict(directory, true);
        file = directory.resolve("inventory.json");
        environment = new MockEnvironment().withProperty("archguard.agent.synthetic-inventory.enabled", "true")
            .withProperty("archguard.agent.synthetic-inventory.file", file.toString())
            .withProperty("archguard.agent.credentials.deployment-id", DEPLOYMENT.toString());
        environment.setActiveProfiles("local-compose");
        provider = new ControlledSyntheticBatchInventory(environment, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        write(document());
    }

    @Test void requiresExplicitProfileEnablementAndValidDeploymentConfiguration() {
        environment.setProperty("archguard.agent.synthetic-inventory.enabled", "false");
        assertThat(resolve()).isEmpty();
        environment.setProperty("archguard.agent.synthetic-inventory.enabled", "not-a-boolean");
        assertThat(resolve()).isEmpty();
        environment.setProperty("archguard.agent.synthetic-inventory.enabled", "true");
        environment.setActiveProfiles("oidc");
        assertThat(resolve()).isEmpty();
        environment.setActiveProfiles("local-compose");
        environment.setProperty("archguard.agent.credentials.deployment-id", "invalid");
        assertThat(resolve()).isEmpty();
        environment.setProperty("archguard.agent.credentials.deployment-id", UUID.randomUUID().toString());
        assertThat(resolve()).isEmpty();
        assertThat(new ControlledSyntheticBatchInventory(new MockEnvironment(), mapper,
            Clock.fixed(NOW, ZoneOffset.UTC)).resolve(PROJECT, INVENTORY, DIGEST)).isEmpty();
    }

    @Test void matchesOnlyExactBindingsAndReturnsImmutableByteBoundProof() throws Exception {
        var proof = resolve().orElseThrow();
        assertThat(proof.schemaVersion()).isEqualTo("0.1.0");
        assertThat(proof.fileSha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        assertThat(proof.fixtureSetVersion()).isEqualTo("samples-synthetic-0.1.0");
        assertThat(proof.fixtureArtifactSha256()).isEqualTo("b".repeat(64));
        assertThat(proof.reviewRef()).isEqualTo("synthetic-review-01");
        assertThat(proof.reviewedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(proof.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(provider.accepts(PROJECT, INVENTORY, DIGEST)).isTrue();
        assertThat(provider.resolve(UUID.randomUUID(), INVENTORY, DIGEST)).isEmpty();
        assertThat(provider.resolve(PROJECT, UUID.randomUUID(), DIGEST)).isEmpty();
        assertThat(provider.resolve(PROJECT, INVENTORY, "c".repeat(64))).isEmpty();
        assertThat(provider.resolve(null, INVENTORY, DIGEST)).isEmpty();
        assertThat(provider.resolve(PROJECT, null, DIGEST)).isEmpty();
        assertThat(provider.resolve(PROJECT, INVENTORY, null)).isEmpty();
    }

    @Test void withdrawalAndByteChangesAreObservedWithoutAStaleGoodCache() throws Exception {
        var original = resolve().orElseThrow();
        assertThat(new ControlledSyntheticBatchInventory(environment, mapper, Clock.fixed(NOW.plusSeconds(601), ZoneOffset.UTC))
            .resolve(PROJECT, INVENTORY, DIGEST)).isEmpty();
        byte[] bytes = Files.readAllBytes(file);
        Files.writeString(file, new String(bytes, StandardCharsets.UTF_8) + "\n");
        assertThat(resolve().orElseThrow().fileSha256()).isNotEqualTo(original.fileSha256());
        Files.writeString(file, "{}");
        assertThat(resolve()).isEmpty();
        write(document());
        assertThat(resolve()).isPresent();
        Files.delete(file);
        assertThat(resolve()).isEmpty();
    }

    @Test void parsesOnlyOneStrictBoundedObject() throws Exception {
        String valid = mapper.writeValueAsString(document());
        Files.writeString(file, valid + " ".repeat(32768 - valid.getBytes(StandardCharsets.UTF_8).length));
        assertThat(resolve()).isPresent();
        Files.writeString(file, valid + " ".repeat(32769 - valid.getBytes(StandardCharsets.UTF_8).length));
        assertThat(resolve()).isEmpty();
        for (String invalid : List.of("", "null", "[]", valid + "{}", valid.replace("\"schemaVersion\":\"0.1.0\"",
                "\"schemaVersion\":\"0.1.0\",\"schemaVersion\":\"0.1.0\""),
                valid.replace("\"reviewRef\":\"synthetic-review-01\"", "\"reviewRef\":7"),
                valid.replace("\"entries\":[", "\"extra\":true,\"entries\":["), " ".repeat(32769))) {
            Files.writeString(file, invalid);
            assertThat(resolve()).as("invalid synthetic inventory shape").isEmpty();
        }
        var extra = document(); ((ObjectNode) extra.get("entries").get(0)).put("extra", "not-allowed");
        write(extra); assertThat(resolve()).isEmpty();
        var nesting = document(); ((ObjectNode) nesting.get("entries").get(0)).putPOJO("reviewRef",
            List.of(List.of(List.of(List.of(List.of(List.of(List.of(List.of(List.of("nested"))))))))));
        write(nesting); assertThat(resolve()).isEmpty();
    }

    @Test void validatesAllEntriesEvenWhenTheFirstOneMatches() throws Exception {
        var duplicate = document(); ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.get("entries")).add(entry(INVENTORY));
        write(duplicate); assertThat(resolve()).isEmpty();
        var malformedOther = document(); var other = entry(UUID.randomUUID()); other.put("sourceKind", "CALLER_ASSERTED");
        ((com.fasterxml.jackson.databind.node.ArrayNode) malformedOther.get("entries")).add(other);
        write(malformedOther); assertThat(resolve()).isEmpty();
        var empty = document(); empty.putArray("entries"); write(empty); assertThat(resolve()).isEmpty();
        var tooMany = document(); var entries = tooMany.putArray("entries");
        for (int index = 0; index < 21; index++) entries.add(entry(UUID.randomUUID()));
        write(tooMany); assertThat(resolve()).isEmpty();
        var maximum = document(); entries = maximum.putArray("entries"); entries.add(entry(INVENTORY));
        for (int index = 1; index < 20; index++) entries.add(entry(UUID.randomUUID()));
        write(maximum); assertThat(resolve()).isPresent();
    }

    @Test void requiresCurrentBoundedReviewAndNonSensitiveProofFields() throws Exception {
        for (var pair : List.of(new String[]{"reviewedAt", NOW.plusSeconds(1).toString()},
                new String[]{"expiresAt", NOW.toString()}, new String[]{"expiresAt", NOW.plusSeconds(86401).toString()},
                new String[]{"sourceKind", "synthetic"}, new String[]{"fixtureSetVersion", ""},
                new String[]{"reviewRef", "sk-synthetic-not-an-approval"}, new String[]{"reviewRef", "ghp_fake-not-an-approval"},
                new String[]{"reviewRef", "github_pat_fake-not-an-approval"}, new String[]{"reviewRef", "a".repeat(129)},
                new String[]{"reviewRef", "非敏感"}, new String[]{"fixtureArtifactSha256", "G".repeat(64)},
                new String[]{"manifestSha256", "a".repeat(63)}, new String[]{"inventoryId", "1-1-1-1-1"})) {
            var document = document(); ((ObjectNode) document.get("entries").get(0)).put(pair[0], pair[1]);
            write(document); assertThat(resolve()).as(pair[0]).isEmpty();
        }
        var old = document(); ((ObjectNode) old.get("entries").get(0)).put("reviewedAt", NOW.minusSeconds(86401).toString());
        write(old); assertThat(resolve()).isEmpty();
        var version = document(); version.put("schemaVersion", "0.2.0"); write(version); assertThat(resolve()).isEmpty();
    }

    @Test void rejectsRelativeNonNormalizedMissingAndDirectoryPaths() {
        for (String path : List.of("inventory.json", directory.resolve("child/../inventory.json").toString(),
                directory.resolve("missing.json").toString(), directory.toString(), "https://example.invalid/inventory.json")) {
            environment.setProperty("archguard.agent.synthetic-inventory.file", path);
            assertThat(resolve()).isEmpty();
        }
    }

    @Test void rejectsOutsideWriteGrantsButAllowsReadOnlyGrants() throws Exception {
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
            assertThat(resolve()).isPresent();
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));
            assertThat(resolve()).isEmpty();
            restrict(file, false);
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwx---"));
            assertThat(resolve()).isEmpty();
        } else {
            var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            var permitted = new java.util.ArrayList<>(acl.getAcl());
            permitted.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(outsider)
                .setPermissions(AclEntryPermission.READ_DATA).build());
            acl.setAcl(permitted); assertThat(resolve()).isPresent();
            permitted.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(outsider)
                .setPermissions(AclEntryPermission.WRITE_DATA).build());
            acl.setAcl(permitted); assertThat(resolve()).isEmpty();
            restrict(file, false);
            var parent = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            permitted = new java.util.ArrayList<>(parent.getAcl());
            permitted.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(outsider)
                .setPermissions(AclEntryPermission.DELETE_CHILD).build());
            parent.setAcl(permitted); assertThat(resolve()).isEmpty();
        }
    }

    @Test @EnabledOnOs(OS.LINUX) void rejectsSymbolicLinksForBothFileAndParent() throws Exception {
        Path link = directory.resolve("link.json"); Files.createSymbolicLink(link, file);
        environment.setProperty("archguard.agent.synthetic-inventory.file", link.toString()); assertThat(resolve()).isEmpty();
        Path parentLink = temporary.resolve("parent-link"); Files.createSymbolicLink(parentLink, directory);
        environment.setProperty("archguard.agent.synthetic-inventory.file", parentLink.resolve("inventory.json").toString());
        assertThat(resolve()).isEmpty();
    }

    private java.util.Optional<io.github.aiarchguard.platform.agent.internal.SyntheticBatchInventory.Proof> resolve() {
        return provider.resolve(PROJECT, INVENTORY, DIGEST);
    }
    private ObjectNode document() {
        var root = mapper.createObjectNode().put("schemaVersion", "0.1.0").put("deploymentId", DEPLOYMENT.toString());
        root.putArray("entries").add(entry(INVENTORY)); return root;
    }
    private ObjectNode entry(UUID inventory) {
        return mapper.createObjectNode().put("inventoryId", inventory.toString()).put("projectId", PROJECT.toString())
            .put("manifestSha256", DIGEST).put("sourceKind", "CONTROLLED_SYNTHETIC_FIXTURES")
            .put("fixtureSetVersion", "samples-synthetic-0.1.0").put("fixtureArtifactSha256", "b".repeat(64))
            .put("reviewRef", "synthetic-review-01").put("reviewedAt", NOW.minusSeconds(60).toString())
            .put("expiresAt", NOW.plusSeconds(600).toString());
    }
    private void write(ObjectNode document) throws Exception {
        Files.write(file, mapper.writeValueAsBytes(document)); restrict(file, false);
    }
    private void restrict(Path path, boolean directory) throws Exception {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } else {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        }
    }
}
