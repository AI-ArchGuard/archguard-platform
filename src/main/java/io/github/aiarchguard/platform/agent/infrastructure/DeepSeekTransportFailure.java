package io.github.aiarchguard.platform.agent.infrastructure;

/** Stable, body-free error. Provider/JDK exceptions must never be retained as its cause. */
final class DeepSeekTransportFailure extends RuntimeException {
    DeepSeekTransportFailure(String code) { super(code); }
}
