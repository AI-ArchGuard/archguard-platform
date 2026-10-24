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

/**
 * 项目下仓库登记的版本化 HTTP 入口，负责请求绑定与响应形态，授权和业务检查委托给公开用例。
 * URL 中的 UUID 只定位资源，创建者身份由应用服务从可信认证上下文取得。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories")
final class RepositoryController {
    private final RepositoryOperations operations;

    RepositoryController(RepositoryOperations operations) {
        this.operations = operations;
    }

    /** 登记现有本地目录，成功返回 201、登记快照及其资源地址；不触发 Git clone 或扫描任务。 */
    @PostMapping
    ResponseEntity<RepositoryView> create(@PathVariable UUID projectId, @Valid @RequestBody CreateRequest request) {
        RepositoryView view = operations.create(projectId, request.key(), request.name(), request.mountPath());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/repositories/" + view.id())).body(view);
    }

    /** 返回当前主体可读项目的全部仓库；当前 API 没有分页。 */
    @GetMapping
    List<RepositoryView> list(@PathVariable UUID projectId) {
        return operations.list(projectId);
    }

    /** 读取项目内的单个仓库；应用层先检查项目成员权限，再确认仓库归属。 */
    @GetMapping("/{repositoryId}")
    RepositoryView get(@PathVariable UUID projectId, @PathVariable UUID repositoryId) {
        return operations.get(projectId, repositoryId);
    }

    /**
     * 创建请求只携带业务标识、展示名称和受控根下的目录路径，不接受创建者或 Scanner identity。
     * Bean Validation 先检查空白与长度；key 格式及目录真实位置由应用层继续校验。
     */
    record CreateRequest(@NotBlank @Size(max = 63) String key, @NotBlank @Size(max = 120) String name,
                         @NotBlank @Size(max = 1024) String mountPath) {
    }
}
