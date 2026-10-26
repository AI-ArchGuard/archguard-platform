package io.github.aiarchguard.platform.governance.web;

import io.github.aiarchguard.platform.governance.BaselineOperations;
import io.github.aiarchguard.platform.governance.BaselineVersionView;
import io.github.aiarchguard.platform.governance.ComparisonView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 基线管理与比较的 HTTP 适配层，只把路径/请求 DTO 交给公开 BaselineOperations。
 * 应用层负责当前主体的 Maintainer/Viewer 权限、仓库/规则/任务归属、成功扫描与不可变快照校验。
 * 比较空间始终由路径中的项目、仓库和输入中的目标分支、规则版本共同确定。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}")
final class BaselineController {
    private final BaselineOperations operations;
    BaselineController(BaselineOperations operations) { this.operations = operations; }

    /** 提升并选中成功扫描，响应中的版本可能被复用；201 和 Location 不代表每次都新增内容版本。 */
    @PostMapping("/baselines")
    ResponseEntity<BaselineVersionView> promote(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                                                 @Valid @RequestBody PromoteRequest request) {
        BaselineVersionView value = operations.promote(projectId, repositoryId, request.targetBranch(),
            request.ruleSetVersionId(), request.scanJobId(), request.commitSha());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/repositories/" + repositoryId
            + "/baselines/" + value.id())).body(value);
    }
    /** 只切换同空间的活动选择，历史基线内容、比较与门禁不会随之改写。 */
    @PostMapping("/baselines/{baselineVersionId}/select")
    BaselineVersionView select(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                               @PathVariable UUID baselineVersionId, @Valid @RequestBody ScopeRequest request) {
        return operations.select(projectId, repositoryId, request.targetBranch(),
            request.ruleSetVersionId(), baselineVersionId);
    }
    /** 读取明确比较空间中的活动版本，缺少兼容基线时由应用异常明确表达。 */
    @GetMapping("/baselines/active")
    BaselineVersionView active(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                               @RequestParam String targetBranch, @RequestParam UUID ruleSetVersionId) {
        return operations.active(projectId, repositoryId, targetBranch, ruleSetVersionId);
    }
    /** 列出同空间不可变版本；尚未建立该空间时返回空列表。 */
    @GetMapping("/baselines")
    List<BaselineVersionView> list(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                                   @RequestParam String targetBranch, @RequestParam UUID ruleSetVersionId) {
        return operations.list(projectId, repositoryId, targetBranch, ruleSetVersionId);
    }
    /** 创建或复用活动基线与候选任务的比较事实，需要写权限，结果本身还不是门禁结论。 */
    @PostMapping("/comparisons")
    ComparisonView compare(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                           @Valid @RequestBody CompareRequest request) {
        return operations.compare(projectId, repositoryId, request.targetBranch(),
            request.ruleSetVersionId(), request.candidateJobId());
    }

    /**
     * 提升输入：targetBranch、ruleSetVersionId 选择比较空间，scanJobId 必须是同仓库/版本的成功任务。
     * commitSha 是 Maintainer 的提交断言，只校验格式和既有绑定，不证明 Git 祖先关系。
     */
    record PromoteRequest(@NotBlank String targetBranch, @NotNull UUID ruleSetVersionId,
                          @NotNull UUID scanJobId, @NotBlank String commitSha) { }
    /** 切换版本时仍需显式给出分支与规则版本，避免仅凭版本 ID 跨比较空间选择。 */
    record ScopeRequest(@NotBlank String targetBranch, @NotNull UUID ruleSetVersionId) { }
    /** 候选比较输入；candidateJobId 必须属于此比较空间，Bean Validation 之后仍有应用语义校验。 */
    record CompareRequest(@NotBlank String targetBranch, @NotNull UUID ruleSetVersionId,
                          @NotNull UUID candidateJobId) { }
}
