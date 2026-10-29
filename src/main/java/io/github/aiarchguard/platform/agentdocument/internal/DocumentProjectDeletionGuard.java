package io.github.aiarchguard.platform.agentdocument.internal;

import io.github.aiarchguard.platform.project.ProjectDeletionGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 通过 Project 的公开删除守卫保留文档历史，不让项目删除级联破坏版本引用。 */
@Component
final class DocumentProjectDeletionGuard implements ProjectDeletionGuard {
    private final DocumentStore store;

    DocumentProjectDeletionGuard(DocumentStore store) { this.store = store; }

    /** 存在任一文档即阻止项目删除；查询只访问文档模块自己的存储。 */
    @Override
    public boolean hasContent(UUID projectId) { return store.hasDocuments(projectId); }
}
