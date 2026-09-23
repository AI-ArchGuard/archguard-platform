package io.github.aiarchguard.platform.governance.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.governance.InvalidGovernanceReportException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Implements the frozen platform-finding-v1 consumer contract, not the Scanner's own fingerprint. */
@Component
final class ReportFingerprintComputer {
    static final String VERSION = "platform-finding-v1";
    private static final Set<String> EDGE_RULES = Set.of("archguard.forbidden-component",
        "archguard.illegal-package-dependency", "archguard.internal-module-access",
        "archguard.layered-architecture", "spring.controller-repository-access");
    private final ObjectMapper mapper;

    ReportFingerprintComputer(ObjectMapper mapper) { this.mapper = mapper; }

    List<FindingSnapshot> compute(byte[] report, String expectedProjectIdentity) {
        try {
            JsonNode root = mapper.readTree(report);
            if (root == null || !"0.1.0".equals(root.path("schemaVersion").asText())
                || !expectedProjectIdentity.equals(root.path("project").path("identity").asText())) {
                throw new InvalidGovernanceReportException("Report schema or project identity is incompatible");
            }
            Map<String, JsonNode> entities = new HashMap<>();
            for (String name : List.of("artifacts", "components", "dependencies", "evidences")) {
                for (JsonNode value : root.path(name)) {
                    if (entities.putIfAbsent(value.path("id").asText(), value) != null) {
                        throw new InvalidGovernanceReportException("Duplicate report identity");
                    }
                }
            }
            Map<String, String> payloads = new HashMap<>();
            List<FindingSnapshot> findings = new ArrayList<>();
            for (JsonNode finding : root.path("findings")) {
                String canonical = canonical(payload(root, finding, entities));
                String fingerprint = sha256(("archguard-finding-v1\n" + canonical).getBytes(StandardCharsets.UTF_8));
                if (payloads.putIfAbsent(fingerprint, canonical) != null) {
                    throw new InvalidGovernanceReportException("Finding fingerprint collision or duplicate logical Finding");
                }
                findings.add(new FindingSnapshot(fingerprint, sha256(canonical.getBytes(StandardCharsets.UTF_8)),
                    required(finding.path("rule"), "id"), required(finding.path("rule"), "version"),
                    required(finding, "severity"), required(finding, "id")));
            }
            return findings.stream().sorted(Comparator.comparing(FindingSnapshot::fingerprint)).toList();
        } catch (IOException exception) {
            throw new InvalidGovernanceReportException("Report is not valid JSON");
        }
    }

    private Map<String, Object> payload(JsonNode root, JsonNode finding, Map<String, JsonNode> entities) {
        String ruleId = required(finding.path("rule"), "id");
        String subjectId = required(finding, "subjectId");
        Map<String, Object> subject;
        Map<String, Object> discriminator;
        if (EDGE_RULES.contains(ruleId)) {
            requirePrefix(subjectId, "dependency_");
            subject = sorted("edge", dependency(entity(entities, subjectId), entities));
            discriminator = sorted("violation", oneExtension(finding, "archguard.violation"));
        } else if ("archguard.dependency-cycle".equals(ruleId)) {
            requirePrefix(subjectId, "dependency_");
            subject = sorted("cycle", cycle(root, finding, entities));
            discriminator = new TreeMap<>();
        } else if ("archguard.complexity-threshold".equals(ruleId)) {
            if (subjectId.startsWith("component_")) {
                subject = sorted("component", component(entity(entities, subjectId), entities));
            } else if (subjectId.startsWith("artifact_")) {
                subject = sorted("artifact", artifact(entity(entities, subjectId)));
            } else {
                throw new InvalidGovernanceReportException("Complexity Finding has an unsupported subject");
            }
            discriminator = sorted("metric", oneExtension(finding, "archguard.metric"));
        } else if ("archguard.required-annotation".equals(ruleId)) {
            requirePrefix(subjectId, "component_");
            subject = sorted("component", component(entity(entities, subjectId), entities));
            discriminator = sorted("requiredAnnotation", oneExtension(finding, "archguard.required-annotation"));
        } else {
            throw new InvalidGovernanceReportException("Unsupported Finding rule");
        }
        Map<String, Object> payload = new TreeMap<>();
        payload.put("projectIdentity", required(root.path("project"), "identity"));
        payload.put("ruleId", ruleId);
        payload.put("ruleVersion", required(finding.path("rule"), "version"));
        payload.put("subject", subject);
        payload.put("discriminator", discriminator);
        return payload;
    }

    private Map<String, Object> cycle(JsonNode root, JsonNode finding, Map<String, JsonNode> entities) {
        Set<String> evidence = strings(finding.path("evidenceIds"));
        Set<String> covered = new HashSet<>();
        Set<String> vertices = new HashSet<>();
        boolean subjectIncluded = false;
        int matched = 0;
        for (JsonNode edge : root.path("dependencies")) {
            Set<String> ids = strings(edge.path("evidenceIds"));
            if (evidence.containsAll(ids)) {
                matched++;
                covered.addAll(ids);
                vertices.add(required(edge, "sourceId"));
                vertices.add(required(edge, "targetId"));
                subjectIncluded |= required(edge, "id").equals(required(finding, "subjectId"));
            }
        }
        if (matched == 0 || !covered.equals(evidence) || !subjectIncluded) {
            throw new InvalidGovernanceReportException("Cycle evidence does not identify a closed dependency set");
        }
        List<Map<String, Object>> identities = new ArrayList<>();
        for (String id : vertices) identities.add(component(entity(entities, id), entities));
        identities.sort(Comparator.comparing(this::canonical));
        Map<String, Object> value = new TreeMap<>();
        value.put("graphScope", oneExtension(finding, "archguard.graph-scope"));
        value.put("vertices", identities);
        return value;
    }

    private Map<String, Object> artifact(JsonNode node) {
        Map<String, Object> result = new TreeMap<>();
        result.put("kind", required(node, "kind"));
        result.put("language", required(node, "language"));
        result.put("path", required(node, "repositoryPath"));
        result.put("qualifiedName", required(node, "qualifiedName"));
        return result;
    }

    private Map<String, Object> component(JsonNode node, Map<String, JsonNode> entities) {
        Map<String, Object> result = new TreeMap<>();
        result.put("artifact", artifact(entity(entities, required(node, "artifactId"))));
        result.put("kind", required(node, "kind"));
        result.put("language", required(node, "language"));
        result.put("qualifiedName", required(node, "qualifiedName"));
        return result;
    }

    private Map<String, Object> dependency(JsonNode node, Map<String, JsonNode> entities) {
        Map<String, Object> result = new TreeMap<>();
        result.put("kind", required(node, "kind"));
        result.put("source", component(entity(entities, required(node, "sourceId")), entities));
        result.put("target", component(entity(entities, required(node, "targetId")), entities));
        return result;
    }

    private String canonical(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    private static Map<String, Object> sorted(String key, Object value) {
        Map<String, Object> map = new TreeMap<>(); map.put(key, value); return map;
    }
    private static JsonNode entity(Map<String, JsonNode> entities, String id) {
        JsonNode value = entities.get(id);
        if (value == null) throw new InvalidGovernanceReportException("Finding references an unknown entity");
        return value;
    }
    private static String required(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isEmpty()) {
            throw new InvalidGovernanceReportException("Finding identity is incomplete");
        }
        return Normalizer.normalize(value.asText(), Normalizer.Form.NFC);
    }
    private static String oneExtension(JsonNode finding, String key) {
        JsonNode values = finding.path("extensions").path(key);
        if (!values.isArray() || values.size() != 1 || !values.get(0).isTextual()) {
            throw new InvalidGovernanceReportException("Finding discriminator is incomplete");
        }
        String value = values.get(0).asText();
        if (value.isEmpty()) throw new InvalidGovernanceReportException("Finding discriminator is empty");
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }
    private static Set<String> strings(JsonNode values) {
        if (!values.isArray()) throw new InvalidGovernanceReportException("Finding evidence is incomplete");
        Set<String> result = new HashSet<>();
        for (JsonNode value : values) result.add(value.asText());
        return result;
    }
    private static void requirePrefix(String id, String prefix) {
        if (!id.startsWith(prefix)) throw new InvalidGovernanceReportException("Finding subject type is incompatible");
    }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
