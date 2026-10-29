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

/**
 * 指定项目、仓库下的规则集 HTTP 入口，将请求 DTO 交给应用用例并返回元数据或版本视图。
 * 路由中的标识只限定目标资源，项目成员角色和资源归属仍由应用层核验。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/rule-sets")
final class RuleSetController {
    private final RuleSetOperations operations;
    RuleSetController(RuleSetOperations operations) { this.operations = operations; }

    /** 创建规则集元数据，返回 201 和新资源地址；请求不包含 YAML，规则版本另行提交。 */
    @PostMapping
    ResponseEntity<RuleSetView> create(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                                       @Valid @RequestBody CreateRuleSetRequest request) {
        RuleSetView value = operations.create(projectId, repositoryId, request.key(), request.name());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/repositories/" + repositoryId
            + "/rule-sets/" + value.id())).body(value);
    }

    /** 列出仓库下的规则集元数据，查看者授权由应用用例执行。 */
    @GetMapping
    List<RuleSetView> list(@PathVariable UUID projectId, @PathVariable UUID repositoryId) {
        return operations.list(projectId, repositoryId);
    }

    /** 提交 YAML 创建新版本，校验成功后返回 201 和精确版本地址，不更新旧版本。 */
    @PostMapping("/{ruleSetId}/versions")
    ResponseEntity<RuleSetVersionView> createVersion(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                                                     @PathVariable UUID ruleSetId,
                                                     @Valid @RequestBody CreateVersionRequest request) {
        RuleSetVersionView value = operations.createVersion(projectId, repositoryId, ruleSetId, request.yaml());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/repositories/" + repositoryId
            + "/rule-sets/" + ruleSetId + "/versions/" + value.id())).body(value);
    }

    /** 读取规则集的历史版本列表，包含保存的 YAML 和追溯信息。 */
    @GetMapping("/{ruleSetId}/versions")
    List<RuleSetVersionView> listVersions(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
                                          @PathVariable UUID ruleSetId) {
        return operations.listVersions(projectId, repositoryId, ruleSetId);
    }

    /** 元数据输入：Bean Validation 先检查非空与长度，key 的完整格式由应用层继续校验。 */
    record CreateRuleSetRequest(@NotBlank @Size(max = 63) String key, @NotBlank @Size(max = 120) String name) { }
    /** YAML 原文输入；这里限制字符串长度，应用层还会按 UTF-8 字节大小和 Scanner 规则继续校验。 */
    record CreateVersionRequest(@NotBlank @Size(max = 1048576) String yaml) { }
}
