package com.semantyca.aivox.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.aivox.service.QueueService;
import com.semantyca.mixpla.dto.queue.livestream.IntroInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.SongInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import com.semantyca.mixpla.service.exceptions.RadioStationException;
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
    private static final long MAX_MESSAGE_AGE_MS = 300_000; // 5 minutes

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

                    // Check message age
                    if (dto.getTimestamp() != null) {
                        long messageAge = System.currentTimeMillis() - dto.getTimestamp();
                        if (messageAge > MAX_MESSAGE_AGE_MS) {
                            LOGGER.warnf("Rejecting old message (age: %dms, messageId: %s)", messageAge, dto.getMessageId());
                            return Uni.createFrom().completionStage(message.nack(
                                    new RuntimeException("Message too old: " + messageAge + "ms")
                            ));
                        }
                    }

                    // NEW: Check scene deadline
                    if (dto.getSceneDeadlineTimestamp() != null) {
                        int totalContentDuration = calculateTotalContentDuration(dto);
                        long currentTime = System.currentTimeMillis();
                        long estimatedCompletionTime = currentTime + (totalContentDuration * 1000L);

                        if (estimatedCompletionTime > dto.getSceneDeadlineTimestamp()) {
                            long timeUntilDeadline = dto.getSceneDeadlineTimestamp() - currentTime;
                            LOGGER.warnf("Rejecting late message - Content duration: %ds, Time until deadline: %dms, " +
                                            "Scene: %s, MessageId: %s",
                                    totalContentDuration, timeUntilDeadline, dto.getSceneTitle(), dto.getMessageId());
                            return Uni.createFrom().completionStage(message.nack(
                                    new RuntimeException(String.format(
                                            "Insufficient time before scene deadline (need %ds, have %dms)",
                                            totalContentDuration, timeUntilDeadline))
                            ));
                        } else {
                            LOGGER.infof("Message accepted - Content duration: %ds, Time until deadline: %dms, Scene: %s",
                                    totalContentDuration, (dto.getSceneDeadlineTimestamp() - currentTime) / 1000,
                                    dto.getSceneTitle());
                        }
                    }

                 /*   if (!ASSIGNED_BRANDS.contains(dto.getBrandSlug())) {
                        LOGGER.warn("Skipping message for unassigned brand: " + dto.getBrandSlug());
                        return Uni.createFrom().completionStage(message.nack(
                                new RuntimeException("Unassigned brand: " + dto.getBrandSlug())
                        ));
                    }*/

                    return queueService.addToQueue(dto)
                            .onItem().invoke(result ->
                                    LOGGER.info("Queue request completed for sceneId: " + dto.getSceneId()))
                            .replaceWithVoid()
                            .onItem().transformToUni(v -> Uni.createFrom().completionStage(message.ack()));
                })
                .onFailure().recoverWithUni(e -> {
                    if (e instanceof RadioStationException) {
                        LOGGER.warnf("RadioStation not available, requeuing message: %s", e.getMessage());
                    } else {
                        LOGGER.error("Failed processing message", e);
                    }
                    return Uni.createFrom().completionStage(message.nack(e));
                });
    }

    private int calculateTotalContentDuration(SongQueueMessageDTO dto) {
        int totalDuration = 0;

        if (dto.getSongs() != null) {
            totalDuration += dto.getSongs().values().stream()
                    .mapToInt(SongInfoDTO::getDurationSeconds)
                    .sum();
        }

        if (dto.getFilePaths() != null) {
            totalDuration += dto.getFilePaths().values().stream()
                    .mapToInt(IntroInfoDTO::getDurationSeconds)
                    .sum();
        }

        return totalDuration;
    }
}