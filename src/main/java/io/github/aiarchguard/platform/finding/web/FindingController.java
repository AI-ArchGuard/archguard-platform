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

@RestController
@RequestMapping("/api/v1/projects/{projectId}/scan-jobs/{jobId}")
final class FindingController {
    private final FindingOperations operations;
    FindingController(FindingOperations operations){this.operations=operations;}
    @GetMapping("/findings") List<FindingView> list(@PathVariable UUID projectId,@PathVariable UUID jobId,
        @RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){
        return operations.list(projectId,jobId,page,size);
    }
    @GetMapping("/evidences/{evidenceId}") EvidenceView evidence(@PathVariable UUID projectId,@PathVariable UUID jobId,
        @PathVariable UUID evidenceId){return operations.evidence(projectId,jobId,evidenceId);}
    @PutMapping("/findings/{findingId}/disposition") FindingView disposition(@PathVariable UUID projectId,
        @PathVariable UUID jobId,@PathVariable UUID findingId,@Valid @RequestBody DispositionRequest request){
        return operations.disposition(projectId,jobId,findingId,request.disposition(),request.reason(),request.version());
    }
    @GetMapping("/findings/{findingId}/dispositions") List<DispositionView> history(@PathVariable UUID projectId,
        @PathVariable UUID jobId,@PathVariable UUID findingId){return operations.dispositionHistory(projectId,jobId,findingId);}
    record DispositionRequest(@NotNull FindingDisposition disposition,@NotBlank @Size(max=1000) String reason,
                              @PositiveOrZero long version){}
}
