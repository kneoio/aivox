package com.semantyca.aivox.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.aivox.service.QueueService;
import com.semantyca.mixpla.dto.queue.livestream.IntroInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.SongInfoDTO;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.service.exceptions.RadioStationException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class QueueConsumer {

    private static final Logger LOGGER = Logger.getLogger(QueueConsumer.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final List<String> ASSIGNED_BRANDS = List.of("lumisonic");
    private static final long MAX_MESSAGE_AGE_MS = 300_000; // 5 minutes

    @Inject
    private MetricPublisher metricPublisher;

    @Inject
    QueueService queueService;

    @Incoming("streaming")
    public Uni<Void> consume(Message<byte[]> message) {
        byte[] payload = message.getPayload();
        final SongQueueMessageDTO[] dtoHolder = new SongQueueMessageDTO[1];

        return Uni.createFrom().item(() -> {
                    try {
                        return objectMapper.readValue(payload, SongQueueMessageDTO.class);
                    } catch (Exception e) {
                        LOGGER.error("Failed to deserialize message", e);
                        throw new RuntimeException(e);
                    }
                })
                .chain(dto -> {
                    dtoHolder[0] = dto;
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
                            metricPublisher.publishMetric(dto.getBrandSlug(), MetricEventType.WARNING, "scene_out_of_deadline",
                                    Map.of( "scene", dto.getSceneTitle()), dto.getTraceId());
                            return Uni.createFrom().completionStage(message.nack(
                                    new RuntimeException(String.format(
                                            "Insufficient time before scene deadline (need %ds, have %dms)",
                                            totalContentDuration, timeUntilDeadline))
                            ));
                        } else {
                            LOGGER.infof("Message accepted - Content duration: %ds, Time until deadline: %dms, Scene: %s",
                                    totalContentDuration, (dto.getSceneDeadlineTimestamp() - currentTime) / 1000,
                                    dto.getSceneTitle());
                            metricPublisher.publishMetric(dto.getBrandSlug(), MetricEventType.INFORMATION, "sound_fragment_stuff_accepted",
                                    Map.of( "scene", dto.getSceneTitle()), dto.getTraceId());
                        }
                    }

                 /*   if (!ASSIGNED_BRANDS.contains(dto.getBrandSlug())) {
                        LOGGER.warn("Skipping message for unassigned brand: " + dto.getBrandSlug());
                        return Uni.createFrom().completionStage(message.nack(
                                new RuntimeException("Unassigned brand: " + dto.getBrandSlug())
                        ));
                    }*/

                    return queueService.addToQueue(dto)
                            .onItem().invoke(result -> {
                                    LOGGER.info("Queue request completed for sceneId: " + dto.getSceneId());
                                    metricPublisher.publishMetric(dto.getBrandSlug(), MetricEventType.INFORMATION, "sound_fragment_stuff_completed",
                                        Map.of("scene", dto.getSceneTitle()), dto.getTraceId());
                            })
                            .replaceWithVoid()
                            .onItem().transformToUni(v -> Uni.createFrom().completionStage(message.ack()));
                })
                .onFailure().recoverWithUni(e -> {
                    String brandSlug = dtoHolder[0] != null ? dtoHolder[0].getBrandSlug() : "unknown";
                    if (e instanceof RadioStationException) {
                        LOGGER.warnf("RadioStation not available, requeuing message: %s", e.getMessage());
                        metricPublisher.publishMetric(brandSlug, MetricEventType.ERROR, "radio_station_not_available",
                                Map.of("error", e.getMessage()));
                    } else {
                        LOGGER.error("Failed processing message", e);
                        metricPublisher.publishMetric(brandSlug, MetricEventType.ERROR, "queue_request_failed",
                                Map.of("error", e.getMessage()));
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