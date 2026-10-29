package io.github.aiarchguard.platform.agentdocument.internal;

import io.github.aiarchguard.platform.agentdocument.DocumentFragmentView;
import io.github.aiarchguard.platform.agentdocument.DocumentPage;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionView;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionPage;
import io.github.aiarchguard.platform.agentdocument.internal.domain.DocumentContent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 文档内部持久化端口；事务、项目权限和输入上界由应用服务组织。 */
public interface DocumentStore {
    /** 通过项目内唯一幂等键抢占上传槽，失败者读取既有请求比较摘要。 */
    boolean claimUpload(UUID projectId, String key, String requestSha256);
    Optional<UploadRequest> findUpload(UUID projectId, String key);
    /** 将本事务创建的版本绑定到尚未完成的上传槽。 */
    void completeUpload(UUID projectId, String key, UUID versionId);
    /** 创建或锁定稳定文档行；必须在分配版本号和插入版本的同一事务中调用。 */
    UUID lockOrCreateDocument(UUID projectId, String documentKey, UUID actorId, Instant now);
    /** 调用方持有文档行锁时计算下一版本号，不单独提供并发原子性。 */
    int nextVersionNumber(UUID documentId);
    /** 追加不可变版本正文和元数据。 */
    void insertVersion(UUID versionId, UUID documentId, UUID projectId, int number, String mediaType,
                       DocumentContent content, UUID actorId, Instant now);
    /** 按解析得到的稳定索引追加片段，保持与版本正文同事务。 */
    void insertFragments(UUID versionId, List<DocumentContent.Fragment> fragments);
    /** 同时限定项目、文档、版本身份，跨项目输入按缺失处理。 */
    Optional<DocumentVersionView> findVersion(UUID projectId, UUID documentId, UUID versionId);
    /** 按项目内确切版本 ID 读取，供公开应用接口解析引用。 */
    Optional<DocumentVersionView> findVersionById(UUID projectId, UUID versionId);
    /** 返回项目文档元数据分页，不含正文。 */
    DocumentPage list(UUID projectId, int page, int size);
    /** 确认文档归属后按版本号倒序分页。 */
    DocumentVersionPage listVersions(UUID projectId, UUID documentId, int page, int size);
    /** 校验显式版本集合是否全部属于项目，调用方据此拒绝部分成功。 */
    int countVersions(UUID projectId, List<UUID> versionIds);
    /** 在显式版本内做大小写不敏感的子串检索，返回不超过 limit 的稳定片段。 */
    List<DocumentFragmentView> search(UUID projectId, List<UUID> versionIds, String query, int limit);
    /** 供项目删除守卫判断是否仍存在文档历史。 */
    boolean hasDocuments(UUID projectId);

    /** 幂等槽的规范请求摘要及完成后的版本引用。 */
    record UploadRequest(String requestSha256, UUID versionId) {}
}
