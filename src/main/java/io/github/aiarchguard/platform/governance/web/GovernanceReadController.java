package io.github.aiarchguard.platform.governance.web;

import io.github.aiarchguard.platform.governance.ComparisonView;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.GithubPullRequestView;
import io.github.aiarchguard.platform.governance.GovernancePage;
import io.github.aiarchguard.platform.governance.GovernanceReadOperations;
import io.github.aiarchguard.platform.governance.PrRevisionDeltaView;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web 治理页面的只读 HTTP 适配层，统一调用 GovernanceReadOperations 做 Viewer 权限和范围核验。
 * 历史、比较和 PR 修订差异都不触发扫描、门禁重算或 GitHub 状态发布。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}")
final class GovernanceReadController {
    private final GovernanceReadOperations reads;
    GovernanceReadController(GovernanceReadOperations reads) { this.reads = reads; }

    /**
     * 强制指定分支和规则版本，可用 PR 编号过滤；默认第 0 页、每页 20 条，应用层限制 size 为 1–50。
     * hasMore 仅来自多取一条的探测，不提供总数或跨请求固定快照。
     */
    @GetMapping("/gate-evaluations")
    GovernancePage<GateEvaluationView> gates(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @RequestParam String targetBranch, @RequestParam UUID ruleSetVersionId,
            @RequestParam(required = false) String pullRequestId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reads.gates(projectId, repositoryId, targetBranch, ruleSetVersionId, pullRequestId, page, size);
    }
    /** 读取不可变比较的完整分类快照，不依据今天的活动基线重新解释历史。 */
    @GetMapping("/comparisons/{comparisonId}")
    ComparisonView comparison(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @PathVariable UUID comparisonId) {
        return reads.comparison(projectId, repositoryId, comparisonId);
    }
    /** 分页返回验签事件建立的已知 PR head 与当前门禁 ID，缺少当前门禁时应显示待评估。 */
    @GetMapping("/github/pull-requests")
    GovernancePage<GithubPullRequestView> pullRequests(@PathVariable UUID projectId,
            @PathVariable UUID repositoryId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return reads.pullRequests(projectId, repositoryId, page, size);
    }
    /**
     * 返回当前与前一个不同可信修订的只读差异；缺少可信历史/报告时返回明确不可用状态。
     * 它的 RESOLVED 是 PR 修订参考下的修复数，不替代基线门禁中的 resolvedCount。
     */
    @GetMapping("/github/pull-requests/{externalId}/revision-delta")
    PrRevisionDeltaView prRevisionDelta(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @PathVariable String externalId, @RequestParam UUID ruleSetVersionId) {
        return reads.prRevisionDelta(projectId, repositoryId, externalId, ruleSetVersionId);
    }
}
