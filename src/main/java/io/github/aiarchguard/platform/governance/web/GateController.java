package io.github.aiarchguard.platform.governance.web;

import io.github.aiarchguard.platform.governance.GateEvaluationOperations;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.PolicyExceptionOperations;
import io.github.aiarchguard.platform.governance.PolicyExceptionScopeType;
import io.github.aiarchguard.platform.governance.PolicyExceptionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}")
final class GateController {
    private final GateEvaluationOperations gates;
    private final PolicyExceptionOperations exceptions;
    GateController(GateEvaluationOperations gates, PolicyExceptionOperations exceptions) {
        this.gates = gates; this.exceptions = exceptions;
    }

    @PostMapping("/gate-evaluations")
    ResponseEntity<GateEvaluationView> evaluate(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody EvaluateRequest request) {
        GateEvaluationView value = gates.evaluate(projectId, repositoryId, request.targetBranch(),
            request.ruleSetVersionId(), request.candidateJobId(), key);
        return ResponseEntity.created(URI.create(base(projectId, repositoryId) + "/gate-evaluations/" + value.id()))
            .body(value);
    }
    @GetMapping("/gate-evaluations/{id}")
    GateEvaluationView gate(@PathVariable UUID projectId, @PathVariable UUID repositoryId, @PathVariable UUID id) {
        return gates.get(projectId, repositoryId, id);
    }
    @PostMapping("/policy-exceptions")
    ResponseEntity<PolicyExceptionView> create(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @Valid @RequestBody CreateExceptionRequest request) {
        PolicyExceptionView value = exceptions.create(projectId, repositoryId, request.targetBranch(),
            request.ruleSetVersionId(), request.scopeType(), request.scopeValue(), request.reason(),
            request.effectiveAt(), request.expiresAt());
        return ResponseEntity.created(URI.create(base(projectId, repositoryId) + "/policy-exceptions/" + value.id()))
            .body(value);
    }
    @PostMapping("/policy-exceptions/{id}/revoke")
    PolicyExceptionView revoke(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @PathVariable UUID id, @Valid @RequestBody RevokeRequest request) {
        return exceptions.revoke(projectId, repositoryId, id, request.reason());
    }
    @GetMapping("/policy-exceptions")
    List<PolicyExceptionView> list(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @RequestParam String targetBranch, @RequestParam UUID ruleSetVersionId) {
        return exceptions.list(projectId, repositoryId, targetBranch, ruleSetVersionId);
    }
    @GetMapping("/policy-exceptions/{id}")
    PolicyExceptionView exception(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @PathVariable UUID id) {
        return exceptions.get(projectId, repositoryId, id);
    }
    private static String base(UUID project, UUID repository) {
        return "/api/v1/projects/" + project + "/repositories/" + repository;
    }
    record EvaluateRequest(@NotBlank String targetBranch, @NotNull UUID ruleSetVersionId,
                           @NotNull UUID candidateJobId) { }
    record CreateExceptionRequest(@NotBlank String targetBranch, @NotNull UUID ruleSetVersionId,
            @NotNull PolicyExceptionScopeType scopeType, @NotBlank String scopeValue,
            @NotBlank String reason, @NotNull Instant effectiveAt, @NotNull Instant expiresAt) { }
    record RevokeRequest(@NotBlank String reason) { }
}
