package io.github.aiarchguard.platform.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.governance.InvalidGovernanceReportException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportFingerprintComputerTest {
    private final ReportFingerprintComputer computer = new ReportFingerprintComputer(new ObjectMapper());
    private static final String REPORT = """
        {
          "schemaVersion":"0.1.0",
          "project":{"identity":"samples:java-architecture-violations"},
          "artifacts":[
            {"id":"artifact_app","kind":"module","language":"java","repositoryPath":"app",
             "qualifiedName":"samples:java-architecture-violations:app"},
            {"id":"artifact_core","kind":"module","language":"java","repositoryPath":"core",
             "qualifiedName":"samples:java-architecture-violations:core"}],
          "components":[
            {"id":"component_source","artifactId":"artifact_app","kind":"type","language":"java",
             "qualifiedName":"com.archguard.samples.violations.app.web.BadController"},
            {"id":"component_target","artifactId":"artifact_core","kind":"type","language":"java",
             "qualifiedName":"com.archguard.samples.violations.core.internal.InternalRepository"}],
          "dependencies":[{"id":"dependency_one","kind":"depends-on","sourceId":"component_source",
                            "targetId":"component_target","evidenceIds":["evidence_one"]}],
          "evidences":[{"id":"evidence_one"}],
          "findings":[{"id":"finding_one","rule":{"id":"archguard.internal-module-access","version":"0.1.0"},
                       "subjectId":"dependency_one","severity":"high","message":"original",
                       "location":{"startLine":8},"evidenceIds":["evidence_one"],
                       "extensions":{"archguard.violation":["internal-module-access"]}}]
        }
        """;

    @Test void matchesPublishedSampleVectorWithoutScannerIdsOrLocations() {
        FindingSnapshot original = computer.compute(bytes(REPORT), "samples:java-architecture-violations").getFirst();
        assertThat(original.fingerprint()).isEqualTo("1735a8929ba30e01c35b56a2c9503f110d4e36296fd0d7ed4381af1d4733b6e0");
        String changed = REPORT.replace("finding_one", "finding_other")
            .replace("dependency_one", "dependency_other").replace("component_source", "component_other")
            .replace("startLine\":8", "startLine\":800").replace("\"high\"", "\"low\"")
            .replace("\"original\"", "\"changed\"");
        assertThat(computer.compute(bytes(changed), "samples:java-architecture-violations").getFirst().fingerprint())
            .isEqualTo(original.fingerprint());
    }

    @Test void changesInRuleOrLogicalEntityChangeFingerprint() {
        String original = computer.compute(bytes(REPORT), "samples:java-architecture-violations").getFirst().fingerprint();
        assertThat(computer.compute(bytes(REPORT.replace("internal-module-access\"]", "another-violation\"]")),
            "samples:java-architecture-violations").getFirst().fingerprint()).isNotEqualTo(original);
        assertThat(computer.compute(bytes(REPORT.replace("BadController", "RenamedController")),
            "samples:java-architecture-violations").getFirst().fingerprint()).isNotEqualTo(original);
    }

    @Test void rejectsDuplicateLogicalFindingAndWrongProject() {
        String duplicate = REPORT.replace("}]\n}", "},{\"id\":\"finding_two\",\"rule\":{\"id\":\"archguard.internal-module-access\",\"version\":\"0.1.0\"},\"subjectId\":\"dependency_one\",\"severity\":\"high\",\"extensions\":{\"archguard.violation\":[\"internal-module-access\"]}}]\n}");
        assertThatThrownBy(() -> computer.compute(bytes(duplicate), "samples:java-architecture-violations"))
            .isInstanceOf(InvalidGovernanceReportException.class);
        assertThatThrownBy(() -> computer.compute(bytes(REPORT), "another-project"))
            .isInstanceOf(InvalidGovernanceReportException.class);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
