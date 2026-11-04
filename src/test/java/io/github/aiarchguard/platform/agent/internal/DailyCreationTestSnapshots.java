package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.DailyAgentOperations.*;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import java.time.Instant;
import java.util.UUID;

/** 测试通过既有构建器冻结真实公开资源，不复制业务摘要或投影算法。 */
public final class DailyCreationTestSnapshots {
    private DailyCreationTestSnapshots() {}
    public static AgentSnapshot build(UUID project, UUID owner, DailyRequestInput input, String binding,
            ScanJobOperations jobs, ReportSubmissionOperations reports, FindingOperations findings,
            DocumentOperations documents, ObjectMapper mapper, Instant at, boolean ordinary) {
        var builder = new AuthorizedAgentSnapshotBuilder(jobs, reports, findings, documents, mapper);
        var s = input.selection();
        var selected = builder.authorize(project, new CreateAgentRequest(s.purpose().name(), s.scanJobId(),
            s.reportSha256(), s.prHeadRevisionId(), s.findingIds(), s.documentVersionIds()));
        var profile = ordinary ? AuthorizedAgentSnapshotBuilder.LEGACY : AuthorizedAgentSnapshotBuilder.DAILY;
        String digest = ordinary ? builder.legacyDigest(selected)
            : builder.dailyDigest(project, owner, input.grantId(), binding, selected, profile);
        return builder.build(selected, new AuthorizedAgentSnapshotBuilder.SnapshotIdentity(
            UUID.randomUUID(), owner, "d".repeat(32), at), profile, digest);
    }
}
