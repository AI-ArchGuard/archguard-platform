package io.github.aiarchguard.platform.agent.infrastructure;

/** Fixed internal classification only; never retain provider values, parser messages or causes. */
final class DeepSeekResponseRejection extends IllegalArgumentException {
    private final Reason reason;

    DeepSeekResponseRejection(Reason reason) {
        super("Invalid DeepSeek wire payload");
        this.reason = reason;
    }

    Reason reason() { return reason; }

    enum Reason {
        HTTP_CONTENT_TYPE("HTTP_RESPONSE"), HTTP_BODY_EMPTY("HTTP_RESPONSE"), HTTP_BODY_LIMIT("HTTP_RESPONSE"),
        RESPONSE_JSON("RESPONSE_JSON"),
        ENVELOPE_FIELDS("RESPONSE_ENVELOPE"), ENVELOPE_OBJECT("RESPONSE_ENVELOPE"),
        ENVELOPE_STATUS("RESPONSE_ENVELOPE"), ENVELOPE_IDENTIFIER("RESPONSE_ENVELOPE"),
        ENVELOPE_MODEL("RESPONSE_ENVELOPE"), ENVELOPE_ERROR("RESPONSE_ENVELOPE"),
        ENVELOPE_STORE("RESPONSE_ENVELOPE"), ENVELOPE_TOOLS("RESPONSE_ENVELOPE"),
        ENVELOPE_BACKGROUND("RESPONSE_ENVELOPE"), PROVIDER_IDENTIFIER_SENSITIVE("RESPONSE_ENVELOPE"),
        ACCOUNTING_FIELDS("RESPONSE_ACCOUNTING"), ACCOUNTING_TOKENS("RESPONSE_ACCOUNTING"),
        ACCOUNTING_CACHE("RESPONSE_ACCOUNTING"), ACCOUNTING_REASONING("RESPONSE_ACCOUNTING"),
        OUTPUT_STRUCTURE("RESPONSE_OUTPUT"), OUTPUT_JSON("RESPONSE_OUTPUT"),
        OUTPUT_CREDENTIAL_ECHO("RESPONSE_OUTPUT"), UNCLASSIFIED("RESPONSE_VALIDATION");

        private final String stage;
        Reason(String stage) { this.stage = stage; }
        String stage() { return stage; }
    }
}
