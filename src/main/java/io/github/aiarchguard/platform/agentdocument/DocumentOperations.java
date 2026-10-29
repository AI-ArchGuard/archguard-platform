package io.github.aiarchguard.platform.agentdocument;

import java.util.List;
import java.util.UUID;

/** 文档模块公开应用边界，统一承担项目授权、不可变上传与有界只读检索。 */
public interface DocumentOperations {
    /** Maintainer 上传；同幂等键同规范输入重放，异输入冲突，新键追加版本。 */
    DocumentUploadResult upload(UUID projectId, String documentKey, String idempotencyKey,
                                String mediaType, String filename, byte[] content);
    /** Viewer 读取项目内文档元数据；分页大小最多 100。 */
    DocumentPage list(UUID projectId, int page, int size);
    /** Viewer 读取指定文档的历史版本摘要。 */
    DocumentVersionPage listVersions(UUID projectId, UUID documentId, int page, int size);
    /** 同时限定项目、文档与版本身份读取正文，不自动选择最新版本。 */
    DocumentVersionView getVersion(UUID projectId, UUID documentId, UUID versionId);
    /** 以项目和版本 ID 解析不可变正文，供跨模块公开调用。 */
    DocumentVersionView getVersionById(UUID projectId, UUID versionId);
    /** 只检索显式选定的 1–10 个版本；任一版本不可见则整体失败，最多返回四段。 */
    List<DocumentFragmentView> search(UUID projectId, List<UUID> versionIds, String query);
}
