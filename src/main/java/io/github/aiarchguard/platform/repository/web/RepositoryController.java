package io.github.aiarchguard.platform.repository.web;

import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.repository.RepositoryView;
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
@RequestMapping("/api/v1/projects/{projectId}/repositories")
final class RepositoryController {
    private final RepositoryOperations operations;

    RepositoryController(RepositoryOperations operations) {
        this.operations = operations;
    }

    @PostMapping
    ResponseEntity<RepositoryView> create(@PathVariable UUID projectId, @Valid @RequestBody CreateRequest request) {
        RepositoryView view = operations.create(projectId, request.key(), request.name(), request.mountPath());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/repositories/" + view.id())).body(view);
    }

    @GetMapping
    List<RepositoryView> list(@PathVariable UUID projectId) {
        return operations.list(projectId);
    }

    @GetMapping("/{repositoryId}")
    RepositoryView get(@PathVariable UUID projectId, @PathVariable UUID repositoryId) {
        return operations.get(projectId, repositoryId);
    }

    record CreateRequest(@NotBlank @Size(max = 63) String key, @NotBlank @Size(max = 120) String name,
                         @NotBlank @Size(max = 1024) String mountPath) {
    }
}
