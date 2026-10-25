package io.github.aiarchguard.platform.finding.web;

import io.github.aiarchguard.platform.finding.DispositionView;
import io.github.aiarchguard.platform.finding.EvidenceView;
import io.github.aiarchguard.platform.finding.FindingDisposition;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.finding.FindingView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 指定项目和扫描任务下的 Finding／Evidence 查询及人工处置 HTTP 入口。
 * 本层绑定路径和校验请求形状，实际 Project Viewer／Maintainer 授权由 FindingOperations 实现先执行。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/scan-jobs/{jobId}")
final class FindingController {
    private final FindingOperations operations;
    FindingController(FindingOperations operations){this.operations=operations;}
    /** 页码从零开始；返回该任务的 Finding 列表，分页响应中没有总数。 */
    @GetMapping("/findings") List<FindingView> list(@PathVariable UUID projectId,@PathVariable UUID jobId,
        @RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){
        return operations.list(projectId,jobId,page,size);
    }
    /** 读取报告规范化后的证据及位置，应用层同时检查项目权限与资源归属。 */
    @GetMapping("/evidences/{evidenceId}") EvidenceView evidence(@PathVariable UUID projectId,@PathVariable UUID jobId,
        @PathVariable UUID evidenceId){return operations.evidence(projectId,jobId,evidenceId);}
    /** 请求的 version 作为期望版本用于条件更新，原因与状态写入本扫描的处置历史。 */
    @PutMapping("/findings/{findingId}/disposition") FindingView disposition(@PathVariable UUID projectId,
        @PathVariable UUID jobId,@PathVariable UUID findingId,@Valid @RequestBody DispositionRequest request){
        return operations.disposition(projectId,jobId,findingId,request.disposition(),request.reason(),request.version());
    }
    /** 授权后查询人工处置的追加历史，不返回 Scanner 原始报告。 */
    @GetMapping("/findings/{findingId}/dispositions") List<DispositionView> history(@PathVariable UUID projectId,
        @PathVariable UUID jobId,@PathVariable UUID findingId){return operations.dispositionHistory(projectId,jobId,findingId);}
    /** 仅包含目标处置、原因和预期版本；操作者取自后端当前身份上下文。 */
    record DispositionRequest(@NotNull FindingDisposition disposition,@NotBlank @Size(max=1000) String reason,
                              @PositiveOrZero long version){}
}
