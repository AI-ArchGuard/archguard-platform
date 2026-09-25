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

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}")
final class BaselineController {
    private final BaselineOperations operations;
    BaselineController(BaselineOperations operations) { this.operations = operations; }

    @PostMapping("/baselines")
    ResponseEntity<BaselineVersionView> promote(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                                                 @Valid @RequestBody PromoteRequest request) {
        BaselineVersionView value = operations.promote(projectId, repositoryId, request.targetBranch(),
            request.ruleSetVersionId(), request.scanJobId(), request.commitSha());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/repositories/" + repositoryId
            + "/baselines/" + value.id())).body(value);
    }
    @PostMapping("/baselines/{baselineVersionId}/select")
    BaselineVersionView select(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                               @PathVariable UUID baselineVersionId, @Valid @RequestBody ScopeRequest request) {
        return operations.select(projectId, repositoryId, request.targetBranch(),
            request.ruleSetVersionId(), baselineVersionId);
    }
    @GetMapping("/baselines/active")
    BaselineVersionView active(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                               @RequestParam String targetBranch, @RequestParam UUID ruleSetVersionId) {
        return operations.active(projectId, repositoryId, targetBranch, ruleSetVersionId);
    }
    @GetMapping("/baselines")
    List<BaselineVersionView> list(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                                   @RequestParam String targetBranch, @RequestParam UUID ruleSetVersionId) {
        return operations.list(projectId, repositoryId, targetBranch, ruleSetVersionId);
    }
    @PostMapping("/comparisons")
    ComparisonView compare(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                           @Valid @RequestBody CompareRequest request) {
        return operations.compare(projectId, repositoryId, request.targetBranch(),
            request.ruleSetVersionId(), request.candidateJobId());
    }

    record PromoteRequest(@NotBlank String targetBranch, @NotNull UUID ruleSetVersionId,
                          @NotNull UUID scanJobId, @NotBlank String commitSha) { }
    record ScopeRequest(@NotBlank String targetBranch, @NotNull UUID ruleSetVersionId) { }
    record CompareRequest(@NotBlank String targetBranch, @NotNull UUID ruleSetVersionId,
                          @NotNull UUID candidateJobId) { }
}
