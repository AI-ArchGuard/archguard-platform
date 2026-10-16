package io.github.aiarchguard.platform.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.internal.LiveResponsePort;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeepSeekProductionAdaptersTest {
    @Test void defaultConstructionAndShutdownCannotInitializeAClientReadAKeyOrProbeTheProvider() throws Exception {
        String before = System.getProperty("jdk.httpclient.disableRetryConnect");
        var credentials = new AtomicInteger();
        try {
            System.setProperty("jdk.httpclient.disableRetryConnect", "false");
            var bundle = new DeepSeekProductionAdapters(new ObjectMapper(), (version, callback) -> credentials.incrementAndGet());
            assertThat(bundle.responses()).isNotNull();
            assertThat(bundle.credentials()).isNotNull();
            bundle.close(); bundle.close();
            assertThat(bundle.awaitTermination(Duration.ofMillis(100))).isTrue();
            assertThatThrownBy(() -> bundle.responses().prepare(null)).isInstanceOf(LiveResponsePort.CallFailure.class);
            assertThat(credentials.get()).isZero();
        } finally {
            if (before == null) System.clearProperty("jdk.httpclient.disableRetryConnect");
            else System.setProperty("jdk.httpclient.disableRetryConnect", before);
        }
    }
}
