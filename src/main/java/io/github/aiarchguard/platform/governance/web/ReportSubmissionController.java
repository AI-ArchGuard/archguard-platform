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

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/report-submissions")
final class ReportSubmissionController {
    private final ReportSubmissionOperations submissions;
    private final ObjectMapper mapper;
    ReportSubmissionController(ReportSubmissionOperations submissions, ObjectMapper mapper) {
        this.submissions = submissions;
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

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
        try { bytes = report.getBytes(); }
        catch (IOException exception) { throw new InvalidGovernanceReportException("Report could not be read"); }
        var accepted = submissions.submit(projectId, repositoryId, key, metadata, bytes);
        return ResponseEntity.status(accepted.replay() ? 200 : 202).body(accepted.submission());
    }
    @GetMapping("/{submissionId}")
    ReportSubmissionView get(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @PathVariable UUID submissionId) {
        return submissions.get(projectId, repositoryId, submissionId);
    }
    @GetMapping("/{submissionId}/gate-evaluation")
    ResponseEntity<?> gate(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @PathVariable UUID submissionId) {
        ReportSubmissionView submission = submissions.get(projectId, repositoryId, submissionId);
        if (submission.status() != ReportSubmissionStatus.COMPLETED) {
            return ResponseEntity.accepted().body(submission);
        }
        return ResponseEntity.ok(submissions.gate(projectId, repositoryId, submissionId));
    }
}
