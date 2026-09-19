package io.github.aiarchguard.platform.scanjob.internal;

import java.util.UUID;

interface RunnerCancellationPort {
    void request(UUID jobId, UUID attemptToken);
}
