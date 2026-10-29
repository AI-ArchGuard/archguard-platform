package io.github.aiarchguard.platform.scanjob.internal;

import io.github.aiarchguard.platform.project.ProjectDeletionGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 向 Project 模块提供删除保护：只要项目仍有任意扫描任务记录，就报告项目含有内容。
 * 只通过本模块存储查询，不替 Project 执行授权，也不级联删除任务或结果。
 */
@Component
final class ScanJobProjectDeletionGuard implements ProjectDeletionGuard {
    private final ScanJobStore store;
    ScanJobProjectDeletionGuard(ScanJobStore store){this.store=store;}
    @Override public boolean hasContent(UUID projectId){return store.existsForProject(projectId);}
}
