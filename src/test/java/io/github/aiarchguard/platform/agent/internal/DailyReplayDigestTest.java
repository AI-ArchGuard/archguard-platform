package io.github.aiarchguard.platform.agent.internal;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionView;
import io.github.aiarchguard.platform.finding.*;
import io.github.aiarchguard.platform.scanjob.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 仅用固定类型历史值验证摘要算法；无数据库伪造、外部端口或重复摘要实现。 */
class DailyReplayDigestTest {
    private final UUID project = UUID.randomUUID(), owner = UUID.randomUUID(), grant = UUID.randomUUID();
    private final UUID scan = UUID.randomUUID(), finding = UUID.randomUUID(), document = UUID.randomUUID(), pr = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-10-30T02:00:00Z");
    private final AuthorizedAgentSnapshotBuilder builder = new AuthorizedAgentSnapshotBuilder(null, null, null, null, new ObjectMapper());
    private CreateAgentRequest input() { return new CreateAgentRequest("PR_SUMMARY", scan, "a".repeat(64), pr, List.of(finding), List.of(document)); }
    private AuthorizedAgentSnapshotBuilder.AuthorizedSelection selected() {
        return new AuthorizedAgentSnapshotBuilder.AuthorizedSelection(project, input(),
            new ScanJobView(scan, project, UUID.randomUUID(), UUID.randomUUID(), ScanJobStatus.SUCCEEDED,
                ScanJobOutcome.PASS, owner, at, at, at, 1, 0, null, null, "a".repeat(64)),
            List.of(new FindingView(finding, project, scan, "f1", "b".repeat(64), "rule", "1", "high", "subject", "合成😀",
                null, FindingDisposition.OPEN, 0, List.of())),
            List.of(new DocumentVersionView(document, UUID.randomUUID(), project, "historical-doc", 1, "text/plain", "合成😀",
                "c".repeat(64), 10, 1, owner, at)));
    }
    private AgentRequestView.VersionBindings stored(AuthorizedAgentSnapshotBuilder.SnapshotProfile profile, String digest) {
        return new AgentRequestView.VersionBindings(scan, "a".repeat(64), pr, List.of(finding),
            List.of(new AgentRequestView.DocumentVersionBinding(document, "c".repeat(64))), profile.summaryPrompt(),
            profile.modelProfile(), profile.protocol(), profile.modelId(), "0.1.0", profile.priceVersion(), digest);
    }
    @Test void storedHistoricalProfilesDriveReplayInsteadOfCurrentConstants() {
        for (String field : List.of("prompt", "modelProfile", "protocol", "modelId", "price")) {
            var current = AuthorizedAgentSnapshotBuilder.DAILY;
            var historical = new AuthorizedAgentSnapshotBuilder.SnapshotProfile(current.explanationPrompt(),
                field.equals("prompt") ? "synthetic-historical-summary" : current.summaryPrompt(),
                field.equals("modelProfile") ? "synthetic-historical-profile" : current.modelProfile(),
                field.equals("protocol") ? "synthetic-historical-protocol" : current.protocol(),
                field.equals("modelId") ? "synthetic-historical-model" : current.modelId(),
                field.equals("price") ? "synthetic-historical-price" : current.priceVersion(), true);
            String original = builder.dailyDigest(project, owner, grant, "d".repeat(64), selected(), historical);
            String now = builder.dailyDigest(project, owner, grant, "d".repeat(64), selected(), current);
            assertNotEquals(original, now);
            assertEquals(original, assertDoesNotThrow(() -> builder.dailyReplayDigest(project, owner, grant,
                "d".repeat(64), input(), stored(historical, original))), field);
        }
    }
    @Test void changedStoredBindingOrIncomingGrantChangesReplayDigest() {
        var profile = AuthorizedAgentSnapshotBuilder.DAILY;
        String original = builder.dailyDigest(project, owner, grant, "d".repeat(64), selected(), profile);
        var stored = stored(profile, original);
        assertEquals(original, assertDoesNotThrow(() -> builder.dailyReplayDigest(project, owner, grant, "d".repeat(64), input(), stored)));
        assertNotEquals(original, assertDoesNotThrow(() -> builder.dailyReplayDigest(project, owner, grant, "e".repeat(64), input(), stored)));
        assertNotEquals(original, assertDoesNotThrow(() -> builder.dailyReplayDigest(project, owner, UUID.randomUUID(), "d".repeat(64), input(), stored)));
        var changedHash = new AgentRequestView.VersionBindings(stored.scanJobId(), stored.reportSha256(), stored.prHeadRevisionId(),
            stored.findingIds(), List.of(new AgentRequestView.DocumentVersionBinding(document, "f".repeat(64))), stored.promptVersion(),
            stored.modelProfileVersion(), stored.modelProtocolVersion(), stored.modelId(), stored.outputSchemaVersion(), stored.priceCatalogVersion(), original);
        assertNotEquals(original, assertDoesNotThrow(() -> builder.dailyReplayDigest(project, owner, grant, "d".repeat(64), input(), changedHash)));
    }
    @Test void replayRejectsChangedDocumentIdentityBeforeUsingStoredContentHash() {
        var profile = AuthorizedAgentSnapshotBuilder.DAILY;
        var stored = stored(profile, builder.dailyDigest(project, owner, grant, "d".repeat(64), selected(), profile));
        var changed = new CreateAgentRequest("PR_SUMMARY", scan, "a".repeat(64), pr, List.of(finding), List.of(UUID.randomUUID()));
        assertThrows(AgentConflictException.class, () -> builder.dailyReplayDigest(project, owner, grant, "d".repeat(64), changed, stored));
    }
}
