package io.github.aiarchguard.platform.agent.infrastructure;

/** 传输层内部的无正文错误载体，以稳定错误码及固定原因分类传递失败，不保留供应商或 JDK 异常 cause。 */
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
