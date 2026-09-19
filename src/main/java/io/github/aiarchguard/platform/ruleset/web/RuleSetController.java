package io.github.aiarchguard.platform.ruleset.web;

import io.github.aiarchguard.platform.ruleset.RuleSetOperations;
import io.github.aiarchguard.platform.ruleset.RuleSetVersionView;
import io.github.aiarchguard.platform.ruleset.RuleSetView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/rule-sets")
final class RuleSetController {
    private final RuleSetOperations operations;
    RuleSetController(RuleSetOperations operations) { this.operations = operations; }

    @PostMapping
    ResponseEntity<RuleSetView> create(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                                       @Valid @RequestBody CreateRuleSetRequest request) {
        RuleSetView value = operations.create(projectId, repositoryId, request.key(), request.name());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/repositories/" + repositoryId
            + "/rule-sets/" + value.id())).body(value);
    }

    @GetMapping
    List<RuleSetView> list(@PathVariable UUID projectId, @PathVariable UUID repositoryId) {
        return operations.list(projectId, repositoryId);
    }

    @PostMapping("/{ruleSetId}/versions")
    ResponseEntity<RuleSetVersionView> createVersion(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                                                     @PathVariable UUID ruleSetId,
                                                     @Valid @RequestBody CreateVersionRequest request) {
        RuleSetVersionView value = operations.createVersion(projectId, repositoryId, ruleSetId, request.yaml());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/repositories/" + repositoryId
            + "/rule-sets/" + ruleSetId + "/versions/" + value.id())).body(value);
    }

    @GetMapping("/{ruleSetId}/versions")
    List<RuleSetVersionView> listVersions(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                                          @PathVariable UUID ruleSetId) {
        return operations.listVersions(projectId, repositoryId, ruleSetId);
    }

    record CreateRuleSetRequest(@NotBlank @Size(max = 63) String key, @NotBlank @Size(max = 120) String name) { }
    record CreateVersionRequest(@NotBlank @Size(max = 1048576) String yaml) { }
}
