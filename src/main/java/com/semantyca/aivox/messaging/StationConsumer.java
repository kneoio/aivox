package com.semantyca.aivox.messaging;

import com.semantyca.aivox.model.LiveRadioStationDTO;
import com.semantyca.aivox.service.RadioDJProcessor;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

@ApplicationScoped
public class StationConsumer {
    
    private static final Logger LOGGER = Logger.getLogger(StationConsumer.class);
    
    @Inject RadioDJProcessor processor;
    
    @Incoming("live-stations")
    public Uni<Void> consume(LiveRadioStationDTO station) {
        LOGGER.info("Consuming station data for brand: " + station.getSlugName());
        
        return processor.process(station)
            .onItem().invoke(result -> 
                LOGGER.info("Successfully processed station: " + result.getBrand() + 
                    ", generated " + result.getAudioFilesGenerated() + " audio files")
            )
            .onFailure().invoke(e -> 
                LOGGER.error("Failed to process station: " + station.getSlugName(), e)
            )
            .replaceWithVoid();
    }
}
