package com.semantyca.aivox.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.aivox.service.QueueService;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class QueueConsumer {

    private static final Logger LOGGER = Logger.getLogger(QueueConsumer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final List<String> ASSIGNED_BRANDS = List.of("lumisonic");

    @Inject
    QueueService queueService;

    @Incoming("streaming")
    public Uni<Void> consume(Message<byte[]> message) {
        byte[] payload = message.getPayload();

        return Uni.createFrom().item(() -> {
                    try {
                        return objectMapper.readValue(payload, SongQueueMessageDTO.class);
                    } catch (Exception e) {
                        LOGGER.error("Failed to deserialize message", e);
                        throw new RuntimeException(e);
                    }
                })
                .chain(dto -> {
                    try {
                        LOGGER.info("Received SongQueueMessageDTO: " +
                                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(dto));
                    } catch (Exception e) {
                        LOGGER.info("Received SongQueueMessageDTO (toString): " + dto);
                    }
                    if (!ASSIGNED_BRANDS.contains(dto.getBrandSlug())) {
                        LOGGER.warn("Skipping message for unassigned brand: " + dto.getBrandSlug());
                        return Uni.createFrom().completionStage(message.nack(
                                new RuntimeException("Unassigned brand: " + dto.getBrandSlug())
                        ));
                    }
                    return queueService.addToQueue(dto)
                            .onItem().invoke(result ->
                                    LOGGER.info("Queue request completed for sceneId: " + dto.getSceneId()))
                            .replaceWithVoid()
                            .onItem().transformToUni(v -> Uni.createFrom().completionStage(message.ack()));
                })
                .onFailure().recoverWithUni(e -> {
                    LOGGER.error("Failed processing message", e);
                    return Uni.createFrom().completionStage(message.nack(e));
                });
    }
}
