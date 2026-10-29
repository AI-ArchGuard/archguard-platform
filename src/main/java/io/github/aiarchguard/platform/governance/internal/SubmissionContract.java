package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.GitRevision;
import io.github.aiarchguard.platform.governance.GovernanceScopeInput;
import io.github.aiarchguard.platform.governance.InvalidGovernanceInputException;
import io.github.aiarchguard.platform.governance.InvalidGovernanceReportException;
import io.github.aiarchguard.platform.governance.PullRequestRef;
import io.github.aiarchguard.platform.governance.ReportSubmissionMetadata;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.UUID;

/**
 * CI 报告提交的输入与摘要契约工具，供提交应用服务和 GitHub 适配器复用格式检查。
 * 校验元数据、原始字节 UTF-8 和 SHA；报告 JSON Schema、引用闭合及项目身份由后续导入端口验证。
 */
final class SubmissionContract {
    static final String VERSION = "archguard-report-submission-v1";
    private static final int MAX_REPORT_BYTES = 50 * 1024 * 1024;
    private SubmissionContract() { }

    /**
     * 接受固定 Scanner/Schema 版本与 GitHub 元数据，返回规范分支等字段后的元数据。
     * 报告必须为 1 字节到 50 MiB，且原始字节 SHA 一致；这里不把 CI 的 commit/PR 断言升级为可信 head。
     */
    static ReportSubmissionMetadata validate(ReportSubmissionMetadata value, byte[] report) {
        if (value == null || value.ruleSetVersionId() == null || value.revision() == null) {
            throw new InvalidGovernanceInputException("Submission metadata is incomplete");
        }
        GitRevision revision = value.revision();
        if (!"github".equals(revision.provider())) {
            throw new InvalidGovernanceInputException("Only the GitHub provider is supported");
        }
        String external = numericId(revision.providerRepositoryId(), "GitHub repository ID");
        String commit = sha(revision.commitSha(), "Commit SHA");
        String branch = GovernanceScopeInput.branch(revision.targetBranch());
        PullRequestRef pr = value.pullRequest();
        if (pr != null) {
            String externalPr = numericId(pr.externalId(), "Pull request ID");
            String head = sha(pr.headSha(), "Pull request head SHA");
            String base = sha(pr.baseSha(), "Pull request base SHA");
            if (!commit.equals(head)) {
                throw new InvalidGovernanceInputException("Pull request head must match the submitted commit");
            }
            pr = new PullRequestRef(externalPr, head, base);
        }
        if (!"0.2.1".equals(value.scannerVersion()) || !"0.1.0".equals(value.schemaVersion())) {
            throw new InvalidGovernanceInputException("Scanner or Result Schema version is unsupported");
        }
        if (report != null && report.length > MAX_REPORT_BYTES) {
            throw new io.github.aiarchguard.platform.governance.GovernanceReportTooLargeException();
        }
        if (report == null || report.length == 0) {
            throw new InvalidGovernanceReportException("Report is empty");
        }
        // 严格解码，拒绝用替换字符掩盖非法字节；只检查编码，不重编码后再计算摘要。
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(report));
        } catch (CharacterCodingException exception) {
            throw new InvalidGovernanceReportException("Report must be UTF-8 JSON");
        }
        if (!sha256(report).equals(value.reportSha256())) {
            throw new InvalidGovernanceReportException("Report SHA-256 does not match the bytes");
        }
        return new ReportSubmissionMetadata(value.ruleSetVersionId(),
            new GitRevision("github", external, commit, branch), pr, "0.2.1", "0.1.0", value.reportSha256());
    }

    /**
     * 对 validate 已接受的字段按版本前缀和固定顺序逐行哈希；可选 PR 缺失写空行，最后恰有一个 LF。
     * UUID 为标准字符串，分支已 NFC 规范化，其余身份限于固定 ASCII 格式；幂等键不参与请求摘要。
     * 此提交摘要包含报告原始字节 SHA，不同于门禁服务的五字段请求摘要。
     */
    static String digest(UUID projectId, UUID repositoryId, ReportSubmissionMetadata value) {
        PullRequestRef pr = value.pullRequest();
        GitRevision revision = value.revision();
        String canonical = String.join("\n", VERSION, projectId.toString(), repositoryId.toString(),
            value.ruleSetVersionId().toString(), revision.provider(), revision.providerRepositoryId(),
            revision.commitSha(), revision.targetBranch(), pr == null ? "" : pr.externalId(),
            pr == null ? "" : pr.headSha(), pr == null ? "" : pr.baseSha(),
            value.scannerVersion(), value.schemaVersion(), value.reportSha256()) + "\n";
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    /** 返回原始输入字节的 SHA-256 小写十六进制，不解析或规范化报告正文。 */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
    static String sha(String value, String field) {
        if (value == null || !value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new InvalidGovernanceInputException(field + " must be 40 or 64 lowercase hex characters");
        }
        return value;
    }
    static String numericId(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,19}")) {
            throw new InvalidGovernanceInputException(field + " must be a positive decimal ID");
        }
        return value;
    }
    /** GitHub owner/name 仅为受限展示名；仓库稳定身份由单独的数字 ID 表达。 */
    static String githubName(String value, String field) {
        if (value == null || value.length() > 100 || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")
            || value.contains("..")) {
            throw new InvalidGovernanceInputException(field + " is invalid");
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
}
