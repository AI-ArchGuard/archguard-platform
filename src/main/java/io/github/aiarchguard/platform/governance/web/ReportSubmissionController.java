package io.github.aiarchguard.platform.governance.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.governance.InvalidGovernanceInputException;
import io.github.aiarchguard.platform.governance.InvalidGovernanceReportException;
import io.github.aiarchguard.platform.governance.ReportSubmissionMetadata;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.governance.ReportSubmissionStatus;
import io.github.aiarchguard.platform.governance.ReportSubmissionView;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * CI multipart 报告提交及轮询的 HTTP 适配层，路径提供项目/仓库，业务权限和报告消费由应用接口处理。
 * 只解析 metadata 并保留 report 原始字节；首次 202 与重放 200 是接收契约，不能据此推断后台线程。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/report-submissions")
final class ReportSubmissionController {
    private final ReportSubmissionOperations submissions;
    private final ObjectMapper mapper;
    ReportSubmissionController(ReportSubmissionOperations submissions, ObjectMapper mapper) {
        this.submissions = submissions;
        // 独立副本严格拒绝未知元数据字段，不改变其他控制器共享 ObjectMapper 的设置。
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    /**
     * JSON 元数据解析失败为 400，报告读取失败为 422；格式、大小、归属和原始摘要由应用层继续核验。
     * 接收、求值、完成关联分段持久化；同键重放可恢复已接受但未完成的提交。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ReportSubmissionView> submit(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @RequestHeader("Idempotency-Key") String key, @RequestPart("metadata") String metadataJson,
            @RequestPart("report") MultipartFile report) {
        ReportSubmissionMetadata metadata;
        try { metadata = mapper.readValue(metadataJson, ReportSubmissionMetadata.class); }
        catch (JsonProcessingException exception) {
            throw new InvalidGovernanceInputException("Submission metadata is invalid JSON or has unknown fields");
        }
        byte[] bytes;
        // 不重新序列化报告，后续 SHA-256 与验收必须针对收到的原始字节。
        try { bytes = report.getBytes(); }
        catch (IOException exception) { throw new InvalidGovernanceReportException("Report could not be read"); }
        var accepted = submissions.submit(projectId, repositoryId, key, metadata, bytes);
        // 当前 submit 调用会继续求值；首次响应可已为 COMPLETED，HTTP 202 不等于仍在异步排队。
        return ResponseEntity.status(accepted.replay() ? 200 : 202).body(accepted.submission());
    }
    /** Viewer 读取提交生命周期，COMPLETED 表示关联门禁完成，并非一定 PASS。 */
    @GetMapping("/{submissionId}")
    ReportSubmissionView get(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @PathVariable UUID submissionId) {
        return submissions.get(projectId, repositoryId, submissionId);
    }
    /** 未完成返回 202 和提交视图，完成后返回 200 和确切门禁，调用方必须按状态识别响应类型。 */
    @GetMapping("/{submissionId}/gate-evaluation")
    ResponseEntity<?> gate(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @PathVariable UUID submissionId) {
        ReportSubmissionView submission = submissions.get(projectId, repositoryId, submissionId);
        // 暂不可用不返回空门禁或隐式 PASS，保留提交 ID 供后续轮询/同键恢复。
        if (submission.status() != ReportSubmissionStatus.COMPLETED) {
            return ResponseEntity.accepted().body(submission);
        }
        return ResponseEntity.ok(submissions.gate(projectId, repositoryId, submissionId));
    }
}
