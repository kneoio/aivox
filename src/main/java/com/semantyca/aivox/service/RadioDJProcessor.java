package com.semantyca.aivox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.aivox.dto.LiveRadioStationDTO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.AllArgsConstructor;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RadioDJProcessor {
    
    private static final Logger LOGGER = Logger.getLogger(RadioDJProcessor.class);
    


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
