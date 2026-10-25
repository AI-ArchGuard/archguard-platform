package io.github.aiarchguard.platform.scanjob.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.finding.AcceptedResult;
import io.github.aiarchguard.platform.finding.ResultAcceptance;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import io.github.aiarchguard.platform.scanjob.ScanJobImportOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobOutcome;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CI 报告导入用例：直接形成已完成任务，复用 Finding 模块的校验与规范化入口，不投递 Runner。
 * 此路径由应用层事务包围任务插入、报告消费和完成更新，同库且加入该事务的写入一同提交或回滚。
 * 与没有整体事务的邮箱收集路径不同，不能将这里的事务保证推广到后台协调器。
 */
@Service
class ScanJobImportService implements ScanJobImportOperations {
    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final RuleSetCatalog ruleSets;
    private final CurrentActorProvider actors;
    private final ScanJobStore jobs;
    private final ResultAcceptance results;
    private final ObjectMapper mapper;
    private final Clock clock;

    ScanJobImportService(ProjectAuthorization projects, RepositoryOperations repositories,
            RuleSetCatalog ruleSets, CurrentActorProvider actors, ScanJobStore jobs,
            ResultAcceptance results, ObjectMapper mapper, Clock clock) {
        this.projects = projects; this.repositories = repositories; this.ruleSets = ruleSets;
        this.actors = actors; this.jobs = jobs; this.results = results; this.mapper = mapper; this.clock = clock;
    }

    /**
     * 使用当前身份上下文做 Maintainer 授权并核对仓库/规则版本归属；jobId 由调用方提供。
     * 先插入临时 QUEUED 记录以满足结果关联，再接受报告并在本事务内直接完成导入。
     */
    @Override @Transactional
    public void importCompleted(UUID projectId, UUID repositoryId, UUID ruleSetVersionId,
            UUID jobId, String scannerVersion, byte[] report) {
        projects.requireMaintainer(projectId);
        var repository = repositories.get(projectId, repositoryId);
        ruleSets.requireVersion(projectId, repositoryId, ruleSetVersionId);
        Instant now = Instant.now(clock);
        UUID actor = actors.currentActor().id();
        // CI 使用专用键 ci:jobId；固定零摘要是此导入记录的占位值，不复用 submit 的请求摘要算法。
        jobs.insert(new ScanJobRecord(jobId, projectId, repositoryId, ruleSetVersionId,
            "ci:" + jobId, "0".repeat(64), ScanJobStatus.QUEUED, null, actor, now, null, null,
            null, 0, null, 0, null, null, null, null, false, null, null));
        AcceptedResult accepted = results.validateAndStore(projectId, jobId, repository.scannerIdentity(),
            scannerVersion, report);
        ScanJobOutcome outcome;
        try {
            // 没有本地 Scanner 退出码，故从已通过契约校验的 findings 是否为空推导规则结论。
            outcome = mapper.readTree(report).path("findings").isEmpty()
                ? ScanJobOutcome.PASS : ScanJobOutcome.VIOLATION;
        } catch (IOException exception) {
            throw new IllegalStateException("Validated Scanner report cannot be parsed", exception);
        }
        if (!jobs.completeImported(jobId, report, accepted.sha256(), accepted.scannerVersion(),
                accepted.schemaVersion(), outcome, Instant.now(clock))) {
            // 状态或 CI 专用键不匹配时拒绝提交；异常会使上面的同库事务写入回滚。
            throw new IllegalStateException("CI scan result could not be committed");
        }
    }
}
