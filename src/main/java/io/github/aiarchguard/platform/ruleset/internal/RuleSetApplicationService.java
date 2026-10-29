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

/**
 * 编排规则集管理用例，并向扫描、治理模块提供版本目录；跨模块信息均通过公开接口取得。
 * 元数据描述一组规则，版本记录校验通过的 YAML 原文、摘要和固定契约版本。
 * 当前类没有覆盖存储与审计的整体事务声明，不能据调用先后推断二者必然原子提交。
 */
@Service
final class RuleSetApplicationService implements RuleSetOperations, RuleSetCatalog {
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{2,62}");
    // 这里只初步提取缩进行中的 identity；正则不是 YAML 解析器，完整规则校验仍交给 Scanner CLI。
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

    /**
     * 先要求项目维护者并确认仓库归属，再建立尚无 YAML 的规则集元数据。
     * 创建者取自当前认证主体；存储成功后追加审计，不由客户端指定创建者。
     */
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
            // 将元数据插入的唯一键冲突转换为规则集 key 冲突，供 Web 层统一映射为 409。
            throw new RuleSetConflictException();
        }
        record(actorId, projectId, "ruleset.create", Map.of("ruleSetId", value.id()));
        return value;
    }

    /** 通过项目查看者和仓库归属检查后，只查询该仓库下的规则集元数据。 */
    @Override
    public List<RuleSetView> list(UUID projectId, UUID repositoryId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        return store.list(projectId, repositoryId);
    }

    /**
     * 维护者提交新版本：先核对规则集归属、字节大小与仓库 identity，再由 Scanner 校验完整规则。
     * 校验成功后追加原文版本并记录审计，旧版本不被覆盖。
     */
    @Override
    public RuleSetVersionView createVersion(UUID projectId, UUID repositoryId, UUID ruleSetId, String yaml) {
        projects.requireMaintainer(projectId);
        RepositoryView repository = repositories.get(projectId, repositoryId);
        store.find(projectId, repositoryId, ruleSetId).orElseThrow(RuleSetNotFoundException::new);
        // HTTP DTO 限制 Java 字符串长度；这里再按实际 UTF-8 字节执行 1 MiB 上限，并复用字节计算摘要。
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
        // nextVersion 读取 MAX+1，唯一约束拒绝重复编号；当前流程没有并发串行化或冲突自动重试。
        RuleSetVersionView version = new RuleSetVersionView(UUID.randomUUID(), ruleSetId,
            store.nextVersion(ruleSetId), yaml, sha256(bytes), SCANNER_VERSION, SCHEMA_VERSION, actorId, Instant.now(clock));
        store.insertVersion(version);
        record(actorId, projectId, "ruleset.version.create",
            Map.of("ruleSetId", ruleSetId, "ruleSetVersionId", version.id(), "sha256", version.sha256()));
        return version;
    }

    /** 查看者先确认规则集属于指定仓库，再读取该规则集按版本号倒序排列的历史。 */
    @Override
    public List<RuleSetVersionView> listVersions(UUID projectId, UUID repositoryId, UUID ruleSetId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        store.find(projectId, repositoryId, ruleSetId).orElseThrow(RuleSetNotFoundException::new);
        return store.listVersions(ruleSetId);
    }

    /**
     * Catalog 只通过存储核对版本归属，不调用 Project 成员鉴权。
     * 用户入口的授权或后台任务的可信上下文由调用方负责建立。
     */
    @Override
    public RuleSetVersionView requireVersion(UUID projectId, UUID repositoryId, UUID versionId) {
        return store.findVersion(projectId, repositoryId, versionId).orElseThrow(RuleSetNotFoundException::new);
    }

    /** 为成功写入关联主体、项目和当前 traceId；调用方只传标识与摘要，不将 YAML 放入审计元数据。 */
    private void record(UUID actor, UUID project, String action, Map<String, Object> metadata) {
        audit.record(new AuditEvent(actor, project, action, AuditResult.SUCCESS, traceIds.currentTraceId(), metadata));
    }

    /** 对提交原文的 UTF-8 字节计算十六进制 SHA-256；不做 YAML 规范化或等价内容合并。 */
    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
