package io.github.aiarchguard.platform.scanjob.web;

import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobResult;
import io.github.aiarchguard.platform.scanjob.ScanJobView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目下扫描任务的 REST 入口，只做请求绑定与 HTTP 响应封装，授权和生命周期规则交给应用接口。
 * 请求只携带资源 ID 和幂等键；操作者来自服务端身份上下文，不由请求体自报。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/scan-jobs")
final class ScanJobController {
    private final ScanJobOperations operations;
    ScanJobController(ScanJobOperations operations) { this.operations = operations; }

    /** 提交仓库和不可变规则版本，返回任务及其资源地址；幂等重放也按当前接口返回 201。 */
    @PostMapping
    ResponseEntity<ScanJobView> submit(@PathVariable UUID projectId,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody SubmitRequest request) {
        ScanJobView value = operations.submit(projectId, request.repositoryId(), request.ruleSetVersionId(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/scan-jobs/" + value.id())).body(value);
    }
    /** 分页读取元数据，默认从第零页取 20 条；不附带报告正文。 */
    @GetMapping List<ScanJobView> list(@PathVariable UUID projectId,
                                       @RequestParam(defaultValue="0") @Min(0) int page,
                                       @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return operations.list(projectId, page, size);
    }
    /** 在路径项目范围内读取任务，应用服务负责 Viewer 授权与归属检查。 */
    @GetMapping("/{jobId}") ScanJobView get(@PathVariable UUID projectId, @PathVariable UUID jobId) {
        return operations.get(projectId, jobId);
    }
    /** 返回记录取消意图后的任务视图；CANCEL_REQUESTED 尚不等于 Runner 已退出。 */
    @PostMapping("/{jobId}/cancel") ScanJobView cancel(@PathVariable UUID projectId, @PathVariable UUID jobId) {
        return operations.cancel(projectId, jobId);
    }
    /** 原字节返回已接受的成功报告，以 SHA-256 生成 ETag；这里没有实现条件 GET 判断。 */
    @GetMapping("/{jobId}/result") ResponseEntity<byte[]> result(@PathVariable UUID projectId, @PathVariable UUID jobId) {
        ScanJobResult value = operations.result(projectId, jobId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.ETAG, "\"" + value.sha256() + "\"").body(value.canonicalJson());
    }
    /** 提交只指定已登记仓库和规则版本，二者非空不代表已通过项目授权或归属校验。 */
    record SubmitRequest(@NotNull UUID repositoryId, @NotNull UUID ruleSetVersionId) { }
}
