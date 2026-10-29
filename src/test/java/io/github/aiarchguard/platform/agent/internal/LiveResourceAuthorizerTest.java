package io.github.aiarchguard.platform.agent.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agentdocument.DocumentFragmentView;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionView;
import io.github.aiarchguard.platform.finding.EvidenceView;
import io.github.aiarchguard.platform.finding.FindingDisposition;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.finding.FindingView;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.project.ProjectAccessView;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.project.ProjectMemberRole;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobOutcome;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import io.github.aiarchguard.platform.scanjob.ScanJobView;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * 通过公开领域端口复核模型输入引用的当前业务事实：Project、扫描、Finding、证据、文档和 PR head 都须与冻结投影一致。使用本地端口替身，不访问其他模块的表。
 */
class LiveResourceAuthorizerTest {
    private static final UUID ACTOR = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SCAN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID FINDING_ONE = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID EVIDENCE_ONE = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID DOCUMENT = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID FINDING_TWO = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID EVIDENCE_TWO = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID PR = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID OTHER = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final String REPORT_SHA = "f".repeat(64);
    private static final String TEXT = "synthetic.rule architecture guidance";
    private static final String DOCUMENT_SHA = sha256(TEXT);
    private static final Instant TIME = Instant.parse("2026-10-10T00:00:00Z");

    @Test
    // 正向场景分别覆盖解释与摘要，仅用公开接口获取事实。
    void validatesCurrentSyntheticExplanationAndSummaryThroughPublicPortsOnly() {
        Fixture fixture = new Fixture();
        assertDoesNotThrow(() -> fixture.authorizer.validate(fixture.snapshot(false)));
        assertDoesNotThrow(() -> fixture.authorizer.validate(fixture.snapshot(true)));
        assertEquals(2, fixture.membershipChecks);
        assertEquals(3, fixture.searches);
        assertEquals(1, fixture.prChecks);
    }

    @Test
    // 每次复核都重新检查授权，先前成功不能成为后续缓存许可。
    void authorizationIsRecheckedWithoutCaching() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        fixture.authorizer.validate(snapshot);
        fixture.member = false;
        assertThrows(ProjectNotFoundException.class, () -> fixture.authorizer.validate(snapshot));
        assertEquals(2, fixture.membershipChecks);
        assertEquals(1, fixture.scanReads);
    }

    @Test
    void rejectsUnexpectedProjectFromAuthorizationPort() {
        Fixture fixture = new Fixture();
        fixture.accessProject = OTHER;
        rejected(fixture, fixture.snapshot(false));
        assertEquals(0, fixture.scanReads);
    }

    @Test
    void requiresExactCompletedScanAndReportHash() {
        for (ScanJobView forged : List.of(scan(OTHER, ScanJobStatus.SUCCEEDED, REPORT_SHA),
                scan(PROJECT, ScanJobStatus.RUNNING, REPORT_SHA),
                scan(PROJECT, ScanJobStatus.SUCCEEDED, "e".repeat(64)),
                new ScanJobView(SCAN, PROJECT, OTHER, OTHER, ScanJobStatus.SUCCEEDED, ScanJobOutcome.PASS,
                    ACTOR, TIME, TIME, null, 1, 1, null, null, REPORT_SHA))) {
            Fixture fixture = new Fixture();
            fixture.scan = forged;
            rejected(fixture, fixture.snapshot(false));
        }
    }

    @Test
    void rejectsFindingFromDifferentProject() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        fixture.findings.put(FINDING_ONE, finding(FINDING_ONE, OTHER, List.of(EVIDENCE_ONE)));
        rejected(fixture, snapshot);
    }

    @Test
    void rejectsEvidenceFromDifferentProjectOrScan() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        fixture.evidence.put(EVIDENCE_ONE, new EvidenceView(EVIDENCE_ONE, OTHER, SCAN,
            "synthetic-evidence", "dependency-edge", "not forwarded", location()));
        rejected(fixture, snapshot);
        fixture.evidence.put(EVIDENCE_ONE, new EvidenceView(EVIDENCE_ONE, PROJECT, OTHER,
            "synthetic-evidence", "dependency-edge", "not forwarded", location()));
        rejected(fixture, snapshot);
    }

    @Test
    // 存储候选本身不是证据来源，伪造 ID 不能靠自洽快照通过。
    void forgedEvidenceIdCannotBeMadeTrueByStoredCandidate() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        var forged = new AgentSnapshot.Candidate("c-evidence-1", "SCANNER_EVIDENCE", PROJECT, SCAN,
            REPORT_SHA, EVIDENCE_TWO, null, null, null, null, "dependency-edge");
        rejected(fixture, candidates(snapshot, List.of(forged, snapshot.candidates().getLast())));
    }

    @Test
    // 同属已选集合也不能把一个 Finding 的证据改挂到另一个 Finding。
    void selectedFindingUnionDoesNotPermitCrossFindingEvidenceRemapping() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(true);
        var first = snapshot.input().findings().getFirst();
        var second = snapshot.input().findings().getLast();
        var remappedFirst = new AgentModelPort.FindingInput(first.findingRef(), first.ruleId(), first.ruleVersion(),
            first.severity(), first.message(), first.subject(), second.evidence());
        var remappedSecond = new AgentModelPort.FindingInput(second.findingRef(), second.ruleId(), second.ruleVersion(),
            second.severity(), second.message(), second.subject(), first.evidence());
        rejected(fixture, input(snapshot, model(snapshot.input(), snapshot.input().evidence(),
            snapshot.input().documents(), List.of(remappedFirst, remappedSecond))));
    }

    @Test
    void findingPayloadMustDescribeActualRuleMessageAndSubject() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        AgentModelPort.ModelInput value = snapshot.input();
        var forged = new AgentModelPort.ModelInput(value.purpose(), value.schemaVersion(), value.promptVersion(),
            value.findingRef(), "forged.rule", value.ruleVersion(), value.severity(), value.message(), value.subject(),
            value.evidence(), value.documents(), value.findings());
        rejected(fixture, input(snapshot, forged));
        fixture.findings.put(FINDING_ONE, new FindingView(FINDING_ONE, PROJECT, SCAN, "synthetic-finding", "fingerprint",
            "synthetic.rule", "1", "HIGH", "changed.subject", "Synthetic finding", location(),
            FindingDisposition.OPEN, 1, List.of(EVIDENCE_ONE)));
        rejected(fixture, snapshot);
    }

    @Test
    // 原始摘要和伪造行号不能扩大允许发送的最小证据投影。
    void rawEvidenceSummaryAndForgedLineNumbersCannotExpandMinimalProjection() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        var raw = new AgentModelPort.EvidenceInput("c-evidence-1", "dependency-edge", "not forwarded", 1, 2);
        rejected(fixture, input(snapshot, model(snapshot.input(), List.of(raw), snapshot.input().documents(), null)));
        var lines = new AgentModelPort.EvidenceInput("c-evidence-1", "dependency-edge", "dependency-edge", 5, 8);
        rejected(fixture, input(snapshot, model(snapshot.input(), List.of(lines), snapshot.input().documents(), null)));
    }

    @Test
    void boundDocumentVersionMustStillExistInSameProject() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        fixture.versions.clear();
        rejected(fixture, snapshot);
        fixture.versions.put(DOCUMENT, version(OTHER, TEXT, DOCUMENT_SHA));
        rejected(fixture, snapshot);
    }

    @Test
    // 元数据哈希不能代替对不可变文档实际内容的核验。
    void versionMetadataHashDoesNotProveAlteredActualContent() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        fixture.versions.put(DOCUMENT, version(PROJECT, "altered synthetic content", DOCUMENT_SHA));
        rejected(fixture, snapshot);
    }

    @Test
    void documentBindingHashMustEqualCurrentImmutableVersion() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        var view = snapshot.view();
        var old = view.bindings();
        var forged = new AgentRequestView.VersionBindings(old.scanJobId(), old.reportSha256(), old.prHeadRevisionId(),
            old.findingIds(), List.of(new AgentRequestView.DocumentVersionBinding(DOCUMENT, "e".repeat(64))),
            old.promptVersion(), old.modelProfileVersion(), old.modelProtocolVersion(), old.modelId(),
            old.outputSchemaVersion(), old.priceCatalogVersion(), old.inputDigest());
        rejected(fixture, new AgentSnapshot(new AgentRequestView(view.id(), view.projectId(), view.requesterId(),
            view.traceId(), view.purpose(), view.state(), forged, null, null, null, TIME, TIME),
            snapshot.input(), snapshot.candidates()));
    }

    @Test
    void rejectsForgedDocumentCandidateVersionHashAndFragmentHash() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        for (var forged : List.of(documentCandidate(OTHER, DOCUMENT_SHA, 0, DOCUMENT_SHA),
                documentCandidate(DOCUMENT, "e".repeat(64), 0, DOCUMENT_SHA),
                documentCandidate(DOCUMENT, DOCUMENT_SHA, 1, DOCUMENT_SHA),
                documentCandidate(DOCUMENT, DOCUMENT_SHA, 0, "e".repeat(64)))) {
            rejected(fixture, candidates(snapshot, List.of(snapshot.candidates().getFirst(), forged)));
        }
    }

    @Test
    // 文档摘录必须能按原有界检索准确重放，不能相信旧候选正文。
    void excerptMustEqualTheExactReplayedBoundedFragment() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        var forged = new AgentModelPort.DocumentInput("c-document", DOCUMENT_SHA, "invented guidance");
        rejected(fixture, input(snapshot, model(snapshot.input(), snapshot.input().evidence(), List.of(forged), null)));
    }

    @Test
    void searchResultHashAndOffsetMustResolveIntoActualVersionContent() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        fixture.fragments = List.of(new DocumentFragmentView(PROJECT, DOCUMENT, DOCUMENT_SHA, 0, 1,
            DOCUMENT_SHA, TEXT));
        rejected(fixture, snapshot);
        fixture.fragments = List.of(new DocumentFragmentView(PROJECT, DOCUMENT, DOCUMENT_SHA, 0, 0,
            sha256("invented guidance"), "invented guidance"));
        rejected(fixture, snapshot);
        fixture.fragments = List.of(new DocumentFragmentView(PROJECT, DOCUMENT, DOCUMENT_SHA, 0, 0,
            "e".repeat(64), TEXT));
        rejected(fixture, snapshot);
    }

    @Test
    void exactFragmentReplayFailureDoesNotTrustTheOldSnapshot() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        fixture.authorizer.validate(snapshot);
        fixture.fragments = List.of();
        rejected(fixture, snapshot);
        assertEquals(2, fixture.searches);
    }

    @Test
    void replayNeverAcceptsUnboundedSearchResults() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        DocumentFragmentView fragment = fixture.fragments.getFirst();
        fixture.fragments = List.of(fragment, fragment, fragment, fragment, fragment);
        rejected(fixture, snapshot);
    }

    @Test
    // PR 当前可信 head 每次复核，旧修订不能继续作为已批准摘要来源。
    void staleAppliedPrHeadIsRejectedOnEveryValidation() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(true);
        fixture.authorizer.validate(snapshot);
        fixture.currentPr = false;
        rejected(fixture, snapshot);
        assertEquals(2, fixture.prChecks);
    }

    @Test
    // 候选集合也必须精确，隐藏、未用或重复项不得夹带。
    void hiddenUnusedOrDuplicateCandidatesCannotSurviveValidation() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        List<AgentSnapshot.Candidate> extra = new ArrayList<>(snapshot.candidates());
        extra.add(new AgentSnapshot.Candidate("c-hidden", "SCANNER_EVIDENCE", PROJECT, SCAN, REPORT_SHA,
            EVIDENCE_TWO, null, null, null, null, "dependency-edge"));
        rejected(fixture, candidates(snapshot, extra));
        extra = new ArrayList<>(snapshot.candidates());
        extra.add(snapshot.candidates().getFirst());
        rejected(fixture, candidates(snapshot, extra));
    }

    @Test
    void explicitNoDocumentTemplateNeedsNoDocumentSearch() {
        Fixture fixture = new Fixture();
        AgentSnapshot snapshot = fixture.snapshot(false);
        var old = snapshot.view().bindings();
        var bindings = new AgentRequestView.VersionBindings(old.scanJobId(), old.reportSha256(), null,
            old.findingIds(), List.of(), old.promptVersion(), old.modelProfileVersion(), old.modelProtocolVersion(),
            old.modelId(), old.outputSchemaVersion(), old.priceCatalogVersion(), old.inputDigest());
        var view = snapshot.view();
        var noDocuments = new AgentSnapshot(new AgentRequestView(view.id(), PROJECT, ACTOR, view.traceId(),
            view.purpose(), view.state(), bindings, null, null, null, TIME, TIME),
            model(snapshot.input(), snapshot.input().evidence(), List.of(), null),
            List.of(snapshot.candidates().getFirst()));
        assertDoesNotThrow(() -> fixture.authorizer.validate(noDocuments));
        assertEquals(0, fixture.searches);
    }

    private static void rejected(Fixture fixture, AgentSnapshot snapshot) {
        assertThrows(AgentEnablementInvalidStateException.class, () -> fixture.authorizer.validate(snapshot));
    }

    private static AgentSnapshot input(AgentSnapshot snapshot, AgentModelPort.ModelInput input) {
        return new AgentSnapshot(snapshot.view(), input, snapshot.candidates());
    }

    private static AgentSnapshot candidates(AgentSnapshot snapshot, List<AgentSnapshot.Candidate> candidates) {
        return new AgentSnapshot(snapshot.view(), snapshot.input(), candidates);
    }

    private static AgentModelPort.ModelInput model(AgentModelPort.ModelInput old,
            List<AgentModelPort.EvidenceInput> evidence, List<AgentModelPort.DocumentInput> documents,
            List<AgentModelPort.FindingInput> findings) {
        return new AgentModelPort.ModelInput(old.purpose(), old.schemaVersion(), old.promptVersion(), old.findingRef(),
            old.ruleId(), old.ruleVersion(), old.severity(), old.message(), old.subject(), evidence, documents, findings);
    }

    private static FindingView.SourceLocation location() {
        return new FindingView.SourceLocation("synthetic/path.java", 1, 1, 2, 1);
    }

    private static FindingView finding(UUID id, UUID project, List<UUID> evidence) {
        return new FindingView(id, project, SCAN, "synthetic-finding", "synthetic-fingerprint", "synthetic.rule",
            "1", "HIGH", "synthetic.module", "Synthetic finding", location(), FindingDisposition.OPEN, 1, evidence);
    }

    private static ScanJobView scan(UUID project, ScanJobStatus status, String report) {
        return new ScanJobView(SCAN, project, OTHER, OTHER, status, ScanJobOutcome.PASS, ACTOR,
            TIME, TIME, TIME, 1, 1, null, null, report);
    }

    private static DocumentVersionView version(UUID project, String content, String hash) {
        return new DocumentVersionView(DOCUMENT, OTHER, project, "synthetic-guidance", 1, "text/markdown",
            content, hash, content.getBytes(StandardCharsets.UTF_8).length, 1, ACTOR, TIME);
    }

    private static AgentSnapshot.Candidate documentCandidate(UUID version, String hash, int index, String fragment) {
        return new AgentSnapshot.Candidate("c-document", "PROJECT_DOCUMENT", PROJECT, null, null, null,
            version, hash, index, fragment, "Document fragment " + index);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T port(Class<T> type, BiFunction<String, Object[], Object> operation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, arguments) -> operation.apply(method.getName(), arguments));
    }

    private static final class Fixture {
        final Map<UUID, FindingView> findings = new HashMap<>();
        final Map<UUID, EvidenceView> evidence = new HashMap<>();
        final Map<UUID, DocumentVersionView> versions = new HashMap<>();
        List<DocumentFragmentView> fragments = List.of(new DocumentFragmentView(PROJECT, DOCUMENT, DOCUMENT_SHA,
            0, 0, DOCUMENT_SHA, TEXT));
        boolean member = true;
        boolean currentPr = true;
        UUID accessProject = PROJECT;
        ScanJobView scan = scan(PROJECT, ScanJobStatus.SUCCEEDED, REPORT_SHA);
        int membershipChecks;
        int scanReads;
        int searches;
        int prChecks;
        final LiveResourceAuthorizer authorizer;

        Fixture() {
            findings.put(FINDING_ONE, finding(FINDING_ONE, PROJECT, List.of(EVIDENCE_ONE)));
            findings.put(FINDING_TWO, finding(FINDING_TWO, PROJECT, List.of(EVIDENCE_TWO)));
            evidence.put(EVIDENCE_ONE, new EvidenceView(EVIDENCE_ONE, PROJECT, SCAN, "synthetic-evidence-one",
                "dependency-edge", "not forwarded", location()));
            evidence.put(EVIDENCE_TWO, new EvidenceView(EVIDENCE_TWO, PROJECT, SCAN, "synthetic-evidence-two",
                "dependency-edge", "not forwarded", location()));
            versions.put(DOCUMENT, version(PROJECT, TEXT, DOCUMENT_SHA));
            ProjectAuthorization projects = port(ProjectAuthorization.class, (method, args) -> {
                assertEquals("requireViewerForActor", method);
                assertEquals(PROJECT, args[0]); assertEquals(ACTOR, args[1]);
                membershipChecks++;
                if (!member) throw new ProjectNotFoundException();
                return new ProjectAccessView(accessProject, "synthetic", "Synthetic", ProjectMemberRole.VIEWER);
            });
            ScanJobOperations jobs = port(ScanJobOperations.class, (method, args) -> {
                assertEquals("get", method);
                assertEquals(PROJECT, args[0]); assertEquals(SCAN, args[1]);
                scanReads++;
                return scan;
            });
            FindingOperations findingPort = port(FindingOperations.class, (method, args) -> {
                assertEquals(PROJECT, args[0]); assertEquals(SCAN, args[1]);
                return switch (method) {
                    case "get" -> findings.get(args[2]);
                    case "evidence" -> evidence.get(args[2]);
                    default -> throw new AssertionError("Unexpected non-read Finding operation");
                };
            });
            DocumentOperations documentPort = port(DocumentOperations.class, (method, args) -> {
                assertEquals(PROJECT, args[0]);
                return switch (method) {
                    case "getVersionById" -> versions.get(args[1]);
                    case "search" -> {
                        assertEquals(List.of(DOCUMENT), args[1]);
                        assertEquals("synthetic.rule", args[2]);
                        searches++;
                        yield fragments;
                    }
                    default -> throw new AssertionError("Unexpected non-read Document operation");
                };
            });
            ReportSubmissionOperations reports = port(ReportSubmissionOperations.class, (method, args) -> {
                assertEquals("matchesCompletedPrRevisionForActor", method);
                assertEquals(PROJECT, args[0]); assertEquals(ACTOR, args[1]);
                assertEquals(SCAN, args[2]); assertEquals(REPORT_SHA, args[3]); assertEquals(PR, args[4]);
                prChecks++;
                return currentPr;
            });
            authorizer = new LiveResourceAuthorizer(projects, jobs, findingPort, documentPort, reports);
        }

        AgentSnapshot snapshot(boolean summary) {
            String purpose = summary ? "PR_SUMMARY" : "FINDING_EXPLANATION";
            String prompt = summary ? "pr-summary-0.1.0" : "finding-explanation-0.1.0";
            List<UUID> ids = summary ? List.of(FINDING_ONE, FINDING_TWO) : List.of(FINDING_ONE);
            var bindings = new AgentRequestView.VersionBindings(SCAN, REPORT_SHA, summary ? PR : null, ids,
                List.of(new AgentRequestView.DocumentVersionBinding(DOCUMENT, DOCUMENT_SHA)), prompt,
                "personal-test-0.1.0", "responses-v1-restricted", "synthetic-model", "0.1.0",
                "synthetic-0.1.0", "d".repeat(64));
            var view = new AgentRequestView(OTHER, PROJECT, ACTOR, "synthetic-trace", purpose, "RUNNING",
                bindings, null, null, null, TIME, TIME);
            List<AgentSnapshot.Candidate> candidates = new ArrayList<>();
            List<AgentModelPort.FindingInput> inputs = new ArrayList<>();
            for (int index = 0; index < ids.size(); index++) {
                FindingView finding = findings.get(ids.get(index));
                String citation = "c-evidence-" + (index + 1);
                UUID evidenceId = finding.evidenceIds().getFirst();
                candidates.add(new AgentSnapshot.Candidate(citation, "SCANNER_EVIDENCE", PROJECT, SCAN,
                    REPORT_SHA, evidenceId, null, null, null, null, "dependency-edge"));
                inputs.add(new AgentModelPort.FindingInput("c-finding-" + (index + 1), finding.ruleId(),
                    finding.ruleVersion(), finding.severity(), finding.message(), finding.subjectId(),
                    List.of(new AgentModelPort.EvidenceInput(citation, "dependency-edge", "dependency-edge", 1, 2))));
            }
            candidates.add(documentCandidate(DOCUMENT, DOCUMENT_SHA, 0, DOCUMENT_SHA));
            var docs = List.of(new AgentModelPort.DocumentInput("c-document", DOCUMENT_SHA, TEXT));
            var first = inputs.getFirst();
            var input = summary
                ? new AgentModelPort.ModelInput(purpose, "0.1.0", prompt, null, null, null, null, null, null,
                    List.of(), docs, List.copyOf(inputs))
                : new AgentModelPort.ModelInput(purpose, "0.1.0", prompt, first.findingRef(), first.ruleId(),
                    first.ruleVersion(), first.severity(), first.message(), first.subject(), first.evidence(), docs, null);
            return new AgentSnapshot(view, input, List.copyOf(candidates));
        }
    }
}
