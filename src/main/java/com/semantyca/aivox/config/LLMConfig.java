package com.semantyca.aivox.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "aivox.llm")
public interface LLMConfig {

    @WithName("anthropic.api-key")
    String getAnthropicApiKey();

    @WithName("elevenlabs.api-key")
    String getElevenLabsApiKey();

    @WithName("elevenlabs.voice-id")
    @WithDefault("nZ5WsS2E2UAALki8m2V6")
    String getElevenLabsVoiceId();

    @WithName("elevenlabs.model-id")
    @WithDefault("eleven_v3")
    String getElevenLabsModelId();

    @WithName("elevenlabs.output-format")
    @WithDefault("mp3_44100_128")
    String getElevenLabsOutputFormat();

    @WithName("modelslab.api-key")
    String getModelslabApiKey();

    @WithName("google.credential-path")
    String getGcpCredentialsPath();


    @WithDefault("http://localhost:8080")
    String getHost();

    @WithName("external.upload.files.path")
    @WithDefault("external_uploads")
    String getPathForExternalServiceUploads();
}
