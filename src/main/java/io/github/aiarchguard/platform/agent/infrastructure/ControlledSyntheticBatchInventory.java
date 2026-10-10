package io.github.aiarchguard.platform.agent.infrastructure;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.internal.SyntheticBatchInventory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Operator-managed provenance only; never loads network sources or performs model calls. */
@Component
final class ControlledSyntheticBatchInventory implements SyntheticBatchInventory {
    private static final int MAX_BYTES = 32768;
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "deploymentId", "entries");
    private static final Set<String> ENTRY_FIELDS = Set.of("inventoryId", "projectId", "manifestSha256", "sourceKind",
        "fixtureSetVersion", "fixtureArtifactSha256", "reviewRef", "reviewedAt", "expiresAt");
    private static final Set<AclEntryPermission> WRITE_PERMISSIONS = EnumSet.of(AclEntryPermission.WRITE_DATA,
        AclEntryPermission.APPEND_DATA, AclEntryPermission.WRITE_NAMED_ATTRS, AclEntryPermission.WRITE_ATTRIBUTES,
        AclEntryPermission.DELETE, AclEntryPermission.DELETE_CHILD, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);
    private final Environment environment;
    private final ObjectMapper mapper;
    private final Clock clock;

    ControlledSyntheticBatchInventory(Environment environment, ObjectMapper mapper, Clock clock) {
        this.environment = environment; this.clock = clock;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(8).maxStringLength(256).maxNumberLength(32).build());
    }

    @Override public Optional<Proof> resolve(UUID project, UUID inventory, String digest) {
        byte[] bytes = null;
        try {
            if (project == null || inventory == null || !hash(digest)
                    || !List.of(environment.getActiveProfiles()).contains("local-compose")
                    || !environment.getProperty("archguard.agent.synthetic-inventory.enabled", Boolean.class, false)) return Optional.empty();
            UUID deployment = uuid(environment.getProperty("archguard.agent.credentials.deployment-id"));
            String configured = environment.getProperty("archguard.agent.synthetic-inventory.file");
            if (configured == null || configured.isBlank()) return Optional.empty();
            Path file = Path.of(configured);
            check(file, false); check(file.getParent(), true);
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            try (var stream = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_BYTES) return Optional.empty();
            check(file, false); check(file.getParent(), true);
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!java.util.Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) return Optional.empty();
            JsonNode root = mapper.readTree(bytes); exactFields(root, ROOT_FIELDS);
            if (!"0.1.0".equals(text(root, "schemaVersion")) || !deployment.equals(uuid(text(root, "deploymentId")))) return Optional.empty();
            JsonNode entries = root.get("entries");
            if (!entries.isArray() || entries.isEmpty() || entries.size() > 20) return Optional.empty();
            Instant now = Instant.now(clock); Set<UUID> ids = new HashSet<>(); Entry match = null;
            for (JsonNode value : entries) {
                Entry entry = entry(value, now);
                if (!ids.add(entry.inventory())) return Optional.empty();
                if (project.equals(entry.project()) && inventory.equals(entry.inventory()) && digest.equals(entry.digest())) match = entry;
            }
            if (match == null) return Optional.empty();
            return Optional.of(new Proof("0.1.0", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                match.fixtureSetVersion(), match.fixtureArtifactSha256(), match.reviewRef(), match.reviewedAt(), match.expiresAt()));
        } catch (Exception unavailable) {
            // Malformed/missing/unsafe operator configuration denies only Agent admission, never core startup.
            return Optional.empty();
        } finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    private Entry entry(JsonNode value, Instant now) {
        exactFields(value, ENTRY_FIELDS);
        UUID inventory = uuid(text(value, "inventoryId")), project = uuid(text(value, "projectId"));
        String digest = text(value, "manifestSha256"), fixture = text(value, "fixtureSetVersion"),
            artifact = text(value, "fixtureArtifactSha256"), review = text(value, "reviewRef");
        Instant reviewed = Instant.parse(text(value, "reviewedAt")), expires = Instant.parse(text(value, "expiresAt"));
        if (!"CONTROLLED_SYNTHETIC_FIXTURES".equals(text(value, "sourceKind")) || !hash(digest) || !hash(artifact)
                || !ref(fixture) || !ref(review) || reviewed.isAfter(now) || !expires.isAfter(now)
                || !expires.isAfter(reviewed) || expires.isAfter(reviewed.plusSeconds(86400))) throw invalid();
        return new Entry(inventory, project, digest, fixture, artifact, review, reviewed, expires);
    }
    private static void exactFields(JsonNode node, Set<String> required) {
        if (node == null || !node.isObject() || node.size() != required.size()) throw invalid();
        var fields = node.fieldNames();
        while (fields.hasNext()) if (!required.contains(fields.next())) throw invalid();
    }
    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }
    private static UUID uuid(String value) {
        if (value == null) throw invalid(); UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) throw invalid(); return parsed;
    }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static boolean ref(String value) {
        return value != null && value.matches("(?!(?i:sk-|ghp_|github_pat_))[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }
    private static void check(Path path, boolean directory) throws IOException {
        if (path == null || !path.isAbsolute() || !path.normalize().equals(path) || !path.toRealPath().equals(path)
                || (directory ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) throw invalid();
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            var permissions = posix.readAttributes().permissions();
            if (permissions.contains(PosixFilePermission.GROUP_WRITE) || permissions.contains(PosixFilePermission.OTHERS_WRITE)) throw invalid();
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw invalid();
        var owner = acl.getOwner();
        for (var value : acl.getAcl()) {
            if (value.type() == AclEntryType.ALLOW && !value.principal().equals(owner)
                    && !value.principal().getName().equalsIgnoreCase("NT AUTHORITY\\SYSTEM")
                    && value.permissions().stream().anyMatch(WRITE_PERMISSIONS::contains)) throw invalid();
        }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Synthetic inventory is unavailable."); }
    private record Entry(UUID inventory, UUID project, String digest, String fixtureSetVersion, String fixtureArtifactSha256,
        String reviewRef, Instant reviewedAt, Instant expiresAt) { }
}
