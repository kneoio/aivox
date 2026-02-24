package com.semantyca.aivox.tts;

import com.semantyca.aivox.model.TtsDTO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

@ApplicationScoped
public class AzureTtsEngine implements TtsEngine {
    
    private static final Logger LOGGER = Logger.getLogger(AzureTtsEngine.class);
    private static final TtsProvider PROVIDER = TtsProvider.AZURE;
    
    @ConfigProperty(name = "tts.azure.api-key")
    String apiKey;
    
    @ConfigProperty(name = "tts.azure.region")
    String region;
    
    private final HttpClient httpClient;
    
    public AzureTtsEngine() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }
    
    @Override
    public Uni<byte[]> generateSpeech(String text, String voiceId, String languageCode) {
        return Uni.createFrom().completionStage(() -> {
            try {
                // Azure TTS SSML format
                String ssml = String.format("""
                    <speak version='1.0' xml:lang='%s'>
                        <voice xml:lang='%s' xml:gender='Female' name='%s'>
                            %s
                        </voice>
                    </speak>
                    """, languageCode, languageCode, voiceId, text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
                
                String token = getAccessToken();
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format("https://%s.tts.speech.microsoft.com/cognitiveservices/v1", region)))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/ssml+xml")
                    .header("X-Microsoft-OutputFormat", "audio-16khz-128kbitrate-mono-mp3")
                    .POST(HttpRequest.BodyPublishers.ofString(ssml))
                    .build();
                
                return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> {
                        if (response.statusCode() == 200) {
                            return response.body();
                        } else {
                            LOGGER.error("Azure TTS error: " + response.statusCode());
                            throw new RuntimeException("Azure TTS error: " + response.statusCode());
                        }
                    });
            } catch (Exception e) {
                LOGGER.error("Failed to call Azure TTS", e);
                throw new RuntimeException("Failed to call Azure TTS", e);
            }
        });
    }
    
    @Override
    public Uni<byte[]> generateDialogue(String dialogueJson, TtsDTO ttsConfig) {
        // Azure TTS doesn't have built-in dialogue support like ElevenLabs
        // For simplicity, we'll use the primary voice for the entire dialogue
        // In production, you might want to parse the JSON and generate separate segments
        return generateSpeech(dialogueJson, ttsConfig.getPrimaryVoice(), "en-US");
    }
    
    private String getAccessToken() throws IOException, InterruptedException {
        String tokenUrl = String.format("https://%s.api.cognitive.microsoft.com/sts/v1.0/issueToken", region);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Ocp-Apim-Subscription-Key", apiKey)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(""))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            return response.body();
        } else {
            throw new RuntimeException("Failed to get Azure access token: " + response.statusCode());
        }
    }
}
