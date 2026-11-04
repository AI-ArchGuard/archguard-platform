package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

/** 固定的类型快照编码；对象递归排序，数组保序，显式保留空值。 */
public final class DailySnapshotHashes {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private DailySnapshotHashes() {}
    public static byte[] canonicalBytes(Object snapshot) {
        try { return MAPPER.writeValueAsString(ordered(MAPPER.valueToTree(snapshot))).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid snapshot", invalid); }
    }
    private static JsonNode ordered(JsonNode node) {
        if (node.isTextual()) validate(node.textValue());
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.properties().forEach(entry -> { validate(entry.getKey()); fields.put(entry.getKey(), ordered(entry.getValue())); });
            var result = MAPPER.createObjectNode(); fields.forEach(result::set); return result;
        }
        if (node.isArray()) {
            var result = MAPPER.createArrayNode(); node.forEach(value -> result.add(ordered(value))); return result;
        }
        return node;
    }
    private static void validate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("Invalid Unicode");
            } else if (Character.isLowSurrogate(ch)) throw new IllegalArgumentException("Invalid Unicode");
        }
    }
    public static String sha256(Object snapshot) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes(snapshot))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
