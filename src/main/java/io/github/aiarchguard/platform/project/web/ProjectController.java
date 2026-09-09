package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.project.ProjectOperations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    @GetMapping
    ProjectListResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ProjectListResponse.from(operations.list(page, size));
    }

    @PatchMapping("/{projectId}")
    ProjectResponse update(@PathVariable UUID projectId, @Valid @RequestBody UpdateProjectRequest request) {
        return ProjectResponse.from(operations.update(projectId, request.name(), request.version()));
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID projectId,
                @RequestParam @PositiveOrZero long version) {
        operations.delete(projectId, version);
    }

    @GetMapping("/{projectId}/members")
    ProjectMemberListResponse listMembers(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ProjectMemberListResponse.from(operations.listMembers(projectId, page, size));
    }

    @PutMapping("/{projectId}/members/{actorId}")
    ProjectMemberResponse setMember(@PathVariable UUID projectId, @PathVariable UUID actorId,
                                    @Valid @RequestBody SetProjectMemberRequest request) {
        return ProjectMemberResponse.from(operations.setMember(projectId, actorId, request.role()));
    }

    @DeleteMapping("/{projectId}/members/{actorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(@PathVariable UUID projectId, @PathVariable UUID actorId) {
        operations.removeMember(projectId, actorId);
    }
}
