package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.project.ProjectOperations;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
final class ProjectController {
    private final ProjectOperations operations;

    ProjectController(ProjectOperations operations) {
        this.operations = operations;
    }

    @PostMapping
    ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse response = ProjectResponse.from(operations.create(request.key(), request.name()));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + response.id())).body(response);
    }

    @GetMapping("/{projectId}")
    ProjectResponse get(@PathVariable UUID projectId) {
        return ProjectResponse.from(operations.get(projectId));
    }
}
