package io.github.aiarchguard.platform.agent.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 预检必须使用真实编码器的完整信封和字节上限。 */
class DeepSeekRequestPreparationTest {
    private AgentModelPort.ModelInput input(String excerpt) {
        return new AgentModelPort.ModelInput("FINDING_EXPLANATION", "0.1.0", "finding-explanation-deepseek-0.1.0",
            "f-synthetic", "rule", "0.1.0", "high", "message", "logical-entity",
            List.of(new AgentModelPort.EvidenceInput("c-synthetic", "DEPENDENCY", "DEPENDENCY", 1, 2)),
            List.of(new AgentModelPort.DocumentInput("d-synthetic", "a".repeat(64), excerpt)), null);
    }

    @Test void fullEnvelopeBoundaryMatchesExistingCodec() throws Exception {
        var codec = new DeepSeekResponsesCodec(new ObjectMapper());
        int base = assertDoesNotThrow(() -> codec.encode(input(""))).length;
        assertTrue(base < 6000);
        var accepted = input("x".repeat(6000 - base));
        byte[] expected = assertDoesNotThrow(() -> codec.encode(accepted));
        assertEquals(6000, expected.length);
        assertThrows(IllegalArgumentException.class, () -> codec.encode(input("x".repeat(6001 - base))));
        var preparation = new DeepSeekRequestPreparation();
        assertArrayEquals(expected, assertDoesNotThrow(() -> preparation.prepare(accepted)));
        assertThrows(IllegalArgumentException.class, () -> preparation.prepare(input("x".repeat(6001 - base))));
    }

    @Test void escapedAndNonAsciiInputsMatchActualCodecBytes() throws Exception {
        var source = input("引号\" slash\\ emoji😀");
        assertArrayEquals(assertDoesNotThrow(() -> new DeepSeekResponsesCodec(new ObjectMapper()).encode(source)),
            assertDoesNotThrow(() -> new DeepSeekRequestPreparation().prepare(source)));
    }
}
