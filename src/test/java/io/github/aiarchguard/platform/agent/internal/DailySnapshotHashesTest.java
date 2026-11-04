package io.github.aiarchguard.platform.agent.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.github.aiarchguard.platform.agent.AgentModelPort;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证日常持久化快照的固定编码，不依赖应用级 JSON 配置。 */
class DailySnapshotHashesTest {
    private AgentModelPort.ModelInput input(String message) {
        return new AgentModelPort.ModelInput("FINDING_EXPLANATION", "0.1.0", "finding-explanation-0.1.0",
            "f1", "rule", "1", "HIGH", message, null, List.of(), List.of(), List.of());
    }

    @Test void canonicalEncodingIncludesNullAndPreservesUnicode() {
        byte[] bytes = assertDoesNotThrow(() -> DailySnapshotHashes.canonicalBytes(input("中文😀")));
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"subject\":null"));
        assertTrue(json.contains("中文😀"));
        assertEquals(assertDoesNotThrow(() -> DailySnapshotHashes.sha256(input("中文😀"))), assertDoesNotThrow(() -> DailySnapshotHashes.sha256(input("中文😀"))));
        assertNotEquals(assertDoesNotThrow(() -> DailySnapshotHashes.sha256(input("中文😀"))), assertDoesNotThrow(() -> DailySnapshotHashes.sha256(input("中文😁"))));
        var first = new java.util.LinkedHashMap<String, Object>();
        first.put("z", null); first.put("a", "中文😀");
        var reversed = new java.util.LinkedHashMap<String, Object>();
        reversed.put("a", "中文😀"); reversed.put("z", null);
        byte[] canonical = assertDoesNotThrow(() -> DailySnapshotHashes.canonicalBytes(first));
        assertEquals("{\"a\":\"中文😀\",\"z\":null}", new String(canonical, StandardCharsets.UTF_8));
        assertArrayEquals(canonical, assertDoesNotThrow(() -> DailySnapshotHashes.canonicalBytes(reversed)));
        var candidate = new AgentSnapshot.Candidate("c1", "EVIDENCE", null, null, "a".repeat(64),
            null, null, null, null, null, "合成证据");
        var changed = new AgentSnapshot.Candidate("c1", "EVIDENCE", null, null, "b".repeat(64),
            null, null, null, null, null, "合成证据");
        String candidatesHash = assertDoesNotThrow(() -> DailySnapshotHashes.sha256(List.of(candidate)));
        assertEquals(candidatesHash, assertDoesNotThrow(() -> DailySnapshotHashes.sha256(List.of(candidate))));
        assertNotEquals(candidatesHash, assertDoesNotThrow(() -> DailySnapshotHashes.sha256(List.of())));
        assertNotEquals(candidatesHash, assertDoesNotThrow(() -> DailySnapshotHashes.sha256(List.of(changed))));
    }

    @Test void rejectsUnpairedSurrogatesBeforeHashing() {
        assertThrows(IllegalArgumentException.class, () -> DailySnapshotHashes.canonicalBytes(input("\uD800")));
        assertThrows(IllegalArgumentException.class, () -> DailySnapshotHashes.canonicalBytes(input("\uDC00")));
    }
}
