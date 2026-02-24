package com.semantyca.aivox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.aivox.llm.LlmClient;
import com.semantyca.aivox.memory.BrandMemoryManager;
import com.semantyca.aivox.messaging.QueueClient;
import com.semantyca.aivox.model.LiveRadioStationDTO;
import com.semantyca.aivox.model.SongPromptDTO;
import com.semantyca.aivox.tts.TtsEngine;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.AllArgsConstructor;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class RadioDJProcessor {
    
    private static final Logger LOGGER = Logger.getLogger(RadioDJProcessor.class);
    
    @Inject LlmClient llmClient;
    @Inject TtsEngine ttsEngine;
    @Inject QueueClient queueClient;
    @Inject
    BrandMemoryManager memoryManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public Uni<ProcessingResult> process(LiveRadioStationDTO station) {
        String brand = station.getSlugName();
        
        // Step 1: Generate intro texts
        return memoryManager.getMemory(brand)
            .chain(memory -> generateIntros(station, memory))
            
            // Step 2: Create audio files
            .chain(intros -> createAudioFiles(intros, station))
            
            // Step 3: Queue to playlist/streaming
            .chain(audioFiles -> queueToPlaylist(audioFiles, station));
    }
    
    private Uni<List<IntroResult>> generateIntros(
            LiveRadioStationDTO station, 
            String memory) {
        
        List<Uni<IntroResult>> introUnis = station.getPrompts().stream()
            .map(prompt -> {
                String fullPrompt = buildPrompt(prompt, memory);
                
                return llmClient.invoke(fullPrompt, prompt.getLlmType())
                    .map(response -> {
                        String text = prompt.isPodcast() 
                            ? parseDialogueJson(response)
                            : parsePlainText(response);
                        
                        if (!prompt.isPodcast()) {
                            memoryManager.add(station.getSlugName(), text);
                        }
                        
                        return new IntroResult(
                            prompt.getSongId(), 
                            text, 
                            prompt.isPodcast()
                        );
                    });
            })
            .toList();
        
        return Uni.join().all(introUnis).andFailFast();
    }
    
    private Uni<List<AudioFile>> createAudioFiles(
            List<IntroResult> intros, 
            LiveRadioStationDTO station) {
        
        List<Uni<AudioFile>> audioUnis = intros.stream()
            .map(intro -> {
                if (intro.isDialogue()) {
                    return ttsEngine.generateDialogue(
                        intro.getText(),
                        station.getTts()
                    ).map(data -> new AudioFile(data, "mp3", intro.getSongId()));
                } else {
                    return ttsEngine.generateSpeech(
                        intro.getText(),
                        station.getTts().getPrimaryVoice(),
                        station.getLanguageTag()
                    ).map(data -> new AudioFile(data, "mp3", intro.getSongId()));
                }
            })
            .toList();
        
        return Uni.join().all(audioUnis).andFailFast();
    }
    
    private Uni<ProcessingResult> queueToPlaylist(
            List<AudioFile> audioFiles, 
            LiveRadioStationDTO station) {
        
        return queueClient.addToQueue(station.getSlugName(), audioFiles)
            .replaceWith(new ProcessingResult(
                station.getSlugName(),
                audioFiles.size(),
                "SUCCESS"
            ))
            .onFailure().invoke(e -> 
                LOGGER.error("Failed to queue audio files for station: " + station.getSlugName(), e)
            );
    }
    
    private String buildPrompt(SongPromptDTO prompt, String memory) {
        return String.format("""
            %s
            %s
            
            Draft input:
            %s
            """, 
            memory != null ? "Recent context:\n" + memory : "",
            prompt.getPrompt(),
            prompt.getDraft()
        );
    }
    
    private String parsePlainText(String response) {
        // Extract plain text from LLM response
        return response.trim();
    }
    
    private String parseDialogueJson(String response) {
        try {
            // Try to parse as JSON dialogue format
            objectMapper.readTree(response);
            return response;
        } catch (Exception e) {
            // If not valid JSON, wrap as single speaker dialogue
            return String.format("[{\"speaker\": \"primary\", \"text\": \"%s\"}]", 
                response.replace("\"", "\\\""));
        }
    }
    
    @lombok.Data
    @AllArgsConstructor
    public static class ProcessingResult {
        private String brand;
        private int audioFilesGenerated;
        private String status;
    }
}
