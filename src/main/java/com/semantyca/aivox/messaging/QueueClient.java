package com.semantyca.aivox.messaging;

import com.semantyca.aivox.service.AudioFile;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class QueueClient {
    
    private static final Logger LOGGER = Logger.getLogger(QueueClient.class);
    
    public Uni<Void> addToQueue(String brand, List<AudioFile> audioFiles) {
        return Uni.createFrom().voidItem()
            .invoke(() -> {
                // In a real implementation, this would send to a message queue
                // For now, we'll just log the action
                LOGGER.info("Adding " + audioFiles.size() + " audio files to queue for brand: " + brand);
                
                // TODO: Implement actual queue client
                // This could be RabbitMQ, Kafka, or any other message broker
                // The message would contain the brand and audio file information
                // for the streaming components to process
            });
    }
}
