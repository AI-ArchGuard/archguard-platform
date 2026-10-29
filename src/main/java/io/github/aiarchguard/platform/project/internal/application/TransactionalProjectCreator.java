package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.project.internal.domain.Project;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 创建项目的事务边界，将项目、创建者的维护者关系及成功审计作为一次提交。
 * 外层应用服务通过独立 Spring Bean 调用本类，使调用经过事务代理；
 * 数据库冲突或审计写入失败向外抛出后，代理会回滚该事务。
 */
@Service
public class TransactionalProjectCreator {
    private final ProjectRepository repository;
    private final AuditRecorder auditRecorder;

    public TransactionalProjectCreator(ProjectRepository repository, AuditRecorder auditRecorder) {
        this.repository = repository;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 保存已经通过输入与创建权限校验的项目，并记录带 traceId 的创建成功事实。
     * 本方法不捕获失败，外层只有在事务调用结束后才能把键冲突转成业务异常并记录失败审计。
     */
    @Transactional
    public void create(Project project, String traceId) {
        repository.insertWithMaintainer(project);
        auditRecorder.record(new AuditEvent(
            project.createdBy(), project.id().value(), "project.create", AuditResult.SUCCESS, traceId,
            Map.of("projectKey", project.key().value())));
    }
}
