package io.github.aiarchguard.platform.finding;

import java.util.UUID;

public interface ResultAcceptance {
    AcceptedResult validateAndStore(UUID projectId, UUID jobId, String expectedProjectIdentity,
                                    String scannerVersion, byte[] reportBytes);
}
