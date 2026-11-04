package io.github.aiarchguard.platform.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentModelPort.ModelInput;

/** 只构造固定编解码器和输出 Schema，无传输、凭据或运行时装配。 */
public final class DeepSeekRequestPreparation {
    private final DeepSeekResponsesCodec codec = new DeepSeekResponsesCodec(new ObjectMapper());
    public byte[] prepare(ModelInput input) { return codec.encode(input); }
}
