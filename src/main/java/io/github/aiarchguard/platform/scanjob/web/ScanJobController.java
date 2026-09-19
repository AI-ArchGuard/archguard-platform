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

@RestController
@RequestMapping("/api/v1/projects/{projectId}/scan-jobs")
final class ScanJobController {
    private final ScanJobOperations operations;
    ScanJobController(ScanJobOperations operations) { this.operations = operations; }

    @PostMapping
    ResponseEntity<ScanJobView> submit(@PathVariable UUID projectId,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody SubmitRequest request) {
        ScanJobView value = operations.submit(projectId, request.repositoryId(), request.ruleSetVersionId(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/scan-jobs/" + value.id())).body(value);
    }
    @GetMapping List<ScanJobView> list(@PathVariable UUID projectId,
                                       @RequestParam(defaultValue="0") @Min(0) int page,
                                       @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return operations.list(projectId, page, size);
    }
    @GetMapping("/{jobId}") ScanJobView get(@PathVariable UUID projectId, @PathVariable UUID jobId) {
        return operations.get(projectId, jobId);
    }
    @PostMapping("/{jobId}/cancel") ScanJobView cancel(@PathVariable UUID projectId, @PathVariable UUID jobId) {
        return operations.cancel(projectId, jobId);
    }
    @GetMapping("/{jobId}/result") ResponseEntity<byte[]> result(@PathVariable UUID projectId, @PathVariable UUID jobId) {
        ScanJobResult value = operations.result(projectId, jobId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.ETAG, "\"" + value.sha256() + "\"").body(value.canonicalJson());
    }
    record SubmitRequest(@NotNull UUID repositoryId, @NotNull UUID ruleSetVersionId) { }
}
