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

/**
 * Project 的 HTTP 入口：把请求参数交给公开的应用接口，再把返回视图转换为响应 DTO。
 * 权限判断和事务由应用层负责；这里不直接访问数据库，也不从请求中采信调用者身份。
 */
@RestController
@RequestMapping("/api/v1/projects")
final class ProjectController {
    private final ProjectOperations operations;

    ProjectController(ProjectOperations operations) {
        this.operations = operations;
    }

    @PostMapping
    // 创建成功返回 201，并通过 Location 告诉客户端新资源的查询地址。
    ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse response = ProjectResponse.from(operations.create(request.key(), request.name()));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + response.id())).body(response);
    }

    @GetMapping("/{projectId}")
    // 是否属于该项目由应用层判断；查不到和无成员关系使用相同的项目不存在语义。
    ProjectResponse get(@PathVariable UUID projectId) {
        return ProjectResponse.from(operations.get(projectId));
    }

    @GetMapping
    // page 从 0 开始；参数校验限制单页大小，实际可见范围由应用层按当前身份过滤。
    ProjectListResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ProjectListResponse.from(operations.list(page, size));
    }

    @PatchMapping("/{projectId}")
    // version 是客户端上次读取的版本，不是要求服务端直接写入的新版本。
    ProjectResponse update(@PathVariable UUID projectId, @Valid @RequestBody UpdateProjectRequest request) {
        return ProjectResponse.from(operations.update(projectId, request.name(), request.version()));
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    // 删除也必须携带期望版本；成功时只返回 204，不返回已删除的项目正文。
    void delete(@PathVariable UUID projectId,
                @RequestParam @PositiveOrZero long version) {
        operations.delete(projectId, version);
    }

    @GetMapping("/{projectId}/members")
    // 查看成员和修改成员是不同权限：只读成员也可以查看自己所属项目的成员列表。
    ProjectMemberListResponse listMembers(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ProjectMemberListResponse.from(operations.listMembers(projectId, page, size));
    }

    @PutMapping("/{projectId}/members/{actorId}")
    // 路径中的 actorId 是被管理的目标成员；执行操作的真实调用者仍由应用层取得。
    ProjectMemberResponse setMember(@PathVariable UUID projectId, @PathVariable UUID actorId,
                                    @Valid @RequestBody SetProjectMemberRequest request) {
        return ProjectMemberResponse.from(operations.setMember(projectId, actorId, request.role()));
    }

    @DeleteMapping("/{projectId}/members/{actorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    // 最后一名维护者等业务约束不在控制器复制，由同一应用用例统一执行。
    void removeMember(@PathVariable UUID projectId, @PathVariable UUID actorId) {
        operations.removeMember(projectId, actorId);
    }
}
