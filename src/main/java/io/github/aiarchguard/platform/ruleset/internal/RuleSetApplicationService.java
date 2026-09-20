package io.github.aiarchguard.platform.ruleset.internal;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.repository.RepositoryView;
import io.github.aiarchguard.platform.ruleset.InvalidRuleSetException;
import io.github.aiarchguard.platform.ruleset.RuleSetConflictException;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import io.github.aiarchguard.platform.ruleset.RuleSetNotFoundException;
import io.github.aiarchguard.platform.ruleset.RuleSetOperations;
import io.github.aiarchguard.platform.ruleset.RuleSetVersionView;
import io.github.aiarchguard.platform.ruleset.RuleSetView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
final class RuleSetApplicationService implements RuleSetOperations, RuleSetCatalog {
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{2,62}");
    private static final Pattern IDENTITY = Pattern.compile("(?m)^[ \\t]+identity:\\s*[\\\"']?([^\\s\\\"']+)[\\\"']?\\s*$");
    private static final int MAX_YAML_BYTES = 1024 * 1024;
    private static final String SCANNER_VERSION = "0.2.1";
    private static final String SCHEMA_VERSION = "0.1.0";

    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final CurrentActorProvider actors;
    private final RuleValidationPort validator;
    private final RuleSetStore store;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final Clock clock;

    RuleSetApplicationService(ProjectAuthorization projects, RepositoryOperations repositories,
                              CurrentActorProvider actors, RuleValidationPort validator, RuleSetStore store,
                              AuditRecorder audit, TraceIdProvider traceIds, Clock clock) {
        this.projects = projects;
        this.repositories = repositories;
        this.actors = actors;
        this.validator = validator;
        this.store = store;
        this.audit = audit;
        this.traceIds = traceIds;
        this.clock = clock;
    }

    @Override
    public RuleSetView create(UUID projectId, UUID repositoryId, String key, String name) {
        projects.requireMaintainer(projectId);
        repositories.get(projectId, repositoryId);
        if (key == null || !KEY.matcher(key).matches() || name == null || name.isBlank() || name.length() > 120) {
            throw new InvalidRuleSetException("Rule set key or name is invalid");
        }
        UUID actorId = actors.currentActor().id();
        RuleSetView value = new RuleSetView(UUID.randomUUID(), projectId, repositoryId, key, name.trim(), actorId,
            Instant.now(clock));
        try {
            store.insert(value);
        } catch (DuplicateKeyException exception) {
            throw new RuleSetConflictException();
        }
        record(actorId, projectId, "ruleset.create", Map.of("ruleSetId", value.id()));
        return value;
    }

    @Override
    public List<RuleSetView> list(UUID projectId, UUID repositoryId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        return store.list(projectId, repositoryId);
    }

    @Override
    public RuleSetVersionView createVersion(UUID projectId, UUID repositoryId, UUID ruleSetId, String yaml) {
        projects.requireMaintainer(projectId);
        RepositoryView repository = repositories.get(projectId, repositoryId);
        store.find(projectId, repositoryId, ruleSetId).orElseThrow(RuleSetNotFoundException::new);
        byte[] bytes = yaml == null ? new byte[0] : yaml.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_YAML_BYTES) {
            throw new InvalidRuleSetException("Rule YAML must contain between 1 byte and 1 MiB");
        }
        Matcher matcher = IDENTITY.matcher(yaml);
        if (!matcher.find() || !repository.scannerIdentity().equals(matcher.group(1))) {
            throw new InvalidRuleSetException("Rule YAML project identity must match the repository scanner identity");
        }
        validator.validate(yaml);
        UUID actorId = actors.currentActor().id();
        RuleSetVersionView version = new RuleSetVersionView(UUID.randomUUID(), ruleSetId,
            store.nextVersion(ruleSetId), yaml, sha256(bytes), SCANNER_VERSION, SCHEMA_VERSION, actorId, Instant.now(clock));
        store.insertVersion(version);
        record(actorId, projectId, "ruleset.version.create",
            Map.of("ruleSetId", ruleSetId, "ruleSetVersionId", version.id(), "sha256", version.sha256()));
        return version;
    }

    @Override
    public List<RuleSetVersionView> listVersions(UUID projectId, UUID repositoryId, UUID ruleSetId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        store.find(projectId, repositoryId, ruleSetId).orElseThrow(RuleSetNotFoundException::new);
        return store.listVersions(ruleSetId);
    }

    @Override
    public RuleSetVersionView requireVersion(UUID projectId, UUID repositoryId, UUID versionId) {
        return store.findVersion(projectId, repositoryId, versionId).orElseThrow(RuleSetNotFoundException::new);
    }

    private void record(UUID actor, UUID project, String action, Map<String, Object> metadata) {
        audit.record(new AuditEvent(actor, project, action, AuditResult.SUCCESS, traceIds.currentTraceId(), metadata));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
