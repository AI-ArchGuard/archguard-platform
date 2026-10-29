package io.github.aiarchguard.platform.governance;

import java.util.List;
import java.util.UUID;

/**
 * 治理模块对外的基线与比较应用接口，由 HTTP 入口调用，内部实现负责授权、归属校验和持久化。
 * 比较空间由 Project、Repository、目标分支和 RuleSetVersion 共同确定，规则版本之间不自动继承基线。
 * 写操作要求当前可信主体具有 Maintainer 权限，读取要求 Viewer；参数中的 UUID 不能替代身份认证。
 */
public interface BaselineOperations {
    /**
     * 将同空间的成功扫描提升并选为活动基线；首次创建不可变版本，同一任务再次提升复用原版本。
     * commitSha 是调用者的提交断言；重新提升若改变已绑定的 commit 或报告摘要则冲突。
     */
    BaselineVersionView promote(UUID projectId, UUID repositoryId, String targetBranch,
                                UUID ruleSetVersionId, UUID scanJobId, String commitSha);
    /** 选择同空间已有的不可变版本，只推进活动选择及其版本号，不改写基线内容或历史比较。 */
    BaselineVersionView select(UUID projectId, UUID repositoryId, String targetBranch,
                               UUID ruleSetVersionId, UUID baselineVersionId);
    /** 读取指定空间的当前活动版本；未建立空间或未选定基线时明确报未找到。 */
    BaselineVersionView active(UUID projectId, UUID repositoryId, String targetBranch, UUID ruleSetVersionId);
    /** 列出该空间的版本；尚未建立空间时返回空列表，与 active 的缺失语义不同。 */
    List<BaselineVersionView> list(UUID projectId, UUID repositoryId, String targetBranch, UUID ruleSetVersionId);
    /**
     * 将成功候选扫描与此刻选中的确切基线版本比较，持久化三类差异，重复版本/任务组合复用原比较。
     * 这是写入比较事实的操作；它既不重新运行 Scanner，也不单独给出门禁结论。
     */
    ComparisonView compare(UUID projectId, UUID repositoryId, String targetBranch,
                           UUID ruleSetVersionId, UUID candidateJobId);
}
