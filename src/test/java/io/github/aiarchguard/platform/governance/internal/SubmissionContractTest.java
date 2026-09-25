package io.github.aiarchguard.platform.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.governance.GitRevision;
import io.github.aiarchguard.platform.governance.InvalidGovernanceInputException;
import io.github.aiarchguard.platform.governance.InvalidGovernanceReportException;
import io.github.aiarchguard.platform.governance.PullRequestRef;
import io.github.aiarchguard.platform.governance.ReportSubmissionMetadata;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubmissionContractTest {
    private final UUID project = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID repository = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID rules = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final byte[] report = "{\"schemaVersion\":\"0.1.0\"}".getBytes(StandardCharsets.UTF_8);

    @Test void digestUsesFrozenOrderedFieldsAndTrailingLf() {
        var metadata = metadata("a".repeat(40), "a".repeat(40), SubmissionContract.sha256(report));
        var validated = SubmissionContract.validate(metadata, report);
        String expected = String.join("\n", "archguard-report-submission-v1", project.toString(),
            repository.toString(), rules.toString(), "github", "123", "a".repeat(40), "main", "7",
            "a".repeat(40), "b".repeat(40), "0.2.1", "0.1.0",
            SubmissionContract.sha256(report)) + "\n";
        assertThat(SubmissionContract.digest(project, repository, validated))
            .isEqualTo(SubmissionContract.sha256(expected.getBytes(StandardCharsets.UTF_8)));
        assertThat(SubmissionContract.digest(project, repository, validated))
            .isNotEqualTo(SubmissionContract.digest(UUID.randomUUID(), repository, validated));
    }

    @Test void wrongReportHashAndPrHeadCannotBeSubmitted() {
        assertThatThrownBy(() -> SubmissionContract.validate(metadata("a".repeat(40), "a".repeat(40),
            "0".repeat(64)), report)).isInstanceOf(InvalidGovernanceReportException.class);
        assertThatThrownBy(() -> SubmissionContract.validate(metadata("a".repeat(40), "c".repeat(40),
            SubmissionContract.sha256(report)), report)).isInstanceOf(InvalidGovernanceInputException.class);
    }

    @Test void unsupportedScannerAndMalformedUtf8FailClosed() {
        var metadata = metadata("a".repeat(40), "a".repeat(40), SubmissionContract.sha256(report));
        var unsupported = new ReportSubmissionMetadata(rules, metadata.revision(), metadata.pullRequest(),
            "0.2.0", "0.1.0", metadata.reportSha256());
        assertThatThrownBy(() -> SubmissionContract.validate(unsupported, report))
            .isInstanceOf(InvalidGovernanceInputException.class);
        byte[] bad = {(byte) 0xc3, (byte) 0x28};
        var badMetadata = metadata("a".repeat(40), "a".repeat(40), SubmissionContract.sha256(bad));
        assertThatThrownBy(() -> SubmissionContract.validate(badMetadata, bad))
            .isInstanceOf(InvalidGovernanceReportException.class);
    }

    private ReportSubmissionMetadata metadata(String commit, String prHead, String hash) {
        return new ReportSubmissionMetadata(rules, new GitRevision("github", "123", commit, "main"),
            new PullRequestRef("7", prHead, "b".repeat(40)), "0.2.1", "0.1.0", hash);
    }
}
