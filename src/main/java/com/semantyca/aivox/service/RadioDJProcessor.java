package com.semantyca.aivox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.aivox.dto.LiveRadioStationDTO;
import com.semantyca.aivox.llm.LlmClient;
import com.semantyca.aivox.messaging.QueueClient;
import com.semantyca.aivox.tts.TtsManager;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.AllArgsConstructor;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RadioDJProcessor {
    
    private static final Logger LOGGER = Logger.getLogger(RadioDJProcessor.class);
    
    @Inject LlmClient llmClient;
    @Inject TtsManager ttsManager;
    @Inject QueueClient queueClient;


    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public Uni<ProcessingResult> process(LiveRadioStationDTO station) {
       return Uni.createFrom().item(new ProcessingResult(station.getSlugName(), 0, "SUCCESS"));
    }

    @lombok.Data
    @AllArgsConstructor
    public static class ProcessingResult {
        private String brand;
        private int audioFilesGenerated;
        private String status;
    }
}
