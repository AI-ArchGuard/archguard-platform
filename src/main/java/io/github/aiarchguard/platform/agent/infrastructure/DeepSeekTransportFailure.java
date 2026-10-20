package io.github.aiarchguard.platform.agent.infrastructure;

/** Stable, body-free error. Provider/JDK exceptions must never be retained as its cause. */
final class DeepSeekTransportFailure extends RuntimeException {
    private final DeepSeekResponseRejection.Reason reason;

    DeepSeekTransportFailure(String code) {
        super(code);
        reason = DeepSeekResponseRejection.Reason.UNCLASSIFIED;
    }

    DeepSeekTransportFailure(DeepSeekResponseRejection.Reason reason) {
        super("OUTPUT_INVALID");
        this.reason = reason;
    }

    DeepSeekResponseRejection.Reason reason() { return reason; }
}
