package com.semantyca.aivox.tts;

import com.semantyca.aivox.model.TtsDTO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@ApplicationScoped
public class ElevenLabsTtsEngine implements TtsEngine {
    
    private static final Logger LOGGER = Logger.getLogger(ElevenLabsTtsEngine.class);
    private static final TtsProvider PROVIDER = TtsProvider.ELEVENLABS;
    
    @ConfigProperty(name = "tts.elevenlabs.api-key")
    String apiKey;
    
    @ConfigProperty(name = "tts.elevenlabs.output-format", defaultValue = "mp3")
    String outputFormat;
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public ElevenLabsTtsEngine() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public Uni<byte[]> generateSpeech(String text, String voiceId, String languageCode) {
        return Uni.createFrom().completionStage(() -> {
            try {
                String requestBody = String.format("""
                    {
                        "text": "%s",
                        "model_id": "eleven_multilingual_v2",
                        "voice_settings": {
                            "stability": 0.75,
                            "similarity_boost": 0.75
                        }
                    }
                    """, text.replace("\"", "\\\""));
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.elevenlabs.io/v1/text-to-speech/" + voiceId))
                    .header("Accept", "audio/mpeg")
                    .header("Content-Type", "application/json")
                    .header("xi-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
                
                return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> {
                        if (response.statusCode() == 200) {
                            return response.body();
                        } else {
                            LOGGER.error("ElevenLabs TTS error: " + response.statusCode());
                            throw new RuntimeException("ElevenLabs TTS error: " + response.statusCode());
                        }
                    });
            } catch (Exception e) {
                LOGGER.error("Failed to call ElevenLabs TTS", e);
                throw new RuntimeException("Failed to call ElevenLabs TTS", e);
            }
        });
    }
    
    @Override
    public Uni<byte[]> generateDialogue(String dialogueJson, TtsDTO ttsConfig) {
        return Uni.createFrom().completionStage(() -> {
            try {
                JsonNode dialogue = objectMapper.readTree(dialogueJson);
                List<byte[]> audioSegments = new ArrayList<>();
                
                // Process each dialogue segment
                for (JsonNode segment : dialogue) {
                    String speaker = segment.path("speaker").asText();
                    String text = segment.path("text").asText();
                    
                    String voiceId = "primary".equals(speaker) 
                        ? ttsConfig.getPrimaryVoice()
                        : ttsConfig.getSecondaryVoice();
                    
                    if (voiceId == null || voiceId.isEmpty()) {
                        LOGGER.warn("Voice ID not found for speaker: " + speaker);
                        continue;
                    }
                    
                    // Generate audio for this segment
                    String requestBody = String.format("""
                        {
                            "text": "%s",
                            "model_id": "eleven_multilingual_v2",
                            "voice_settings": {
                                "stability": 0.75,
                                "similarity_boost": 0.75
                            }
                        }
                        """, text.replace("\"", "\\\""));
                    
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.elevenlabs.io/v1/text-to-speech/" + voiceId))
                        .header("Accept", "audio/mpeg")
                        .header("Content-Type", "application/json")
                        .header("xi-api-key", apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                    
                    HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    
                    if (response.statusCode() == 200) {
                        audioSegments.add(response.body());
                    } else {
                        LOGGER.error("Failed to generate TTS for segment: " + response.statusCode());
                    }
                }
                
                // Merge audio segments (simplified - in production, use proper audio merging)
                int totalLength = audioSegments.stream().mapToInt(arr -> arr.length).sum();
                byte[] mergedAudio = new byte[totalLength];
                int offset = 0;
                for (byte[] segment : audioSegments) {
                    System.arraycopy(segment, 0, mergedAudio, offset, segment.length);
                    offset += segment.length;
                }
                
                return java.util.concurrent.CompletableFuture.completedFuture(mergedAudio);
            } catch (Exception e) {
                LOGGER.error("Failed to generate dialogue TTS", e);
                throw new RuntimeException("Failed to generate dialogue TTS", e);
            }
        });
    }
}
