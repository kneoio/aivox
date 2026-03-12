package com.semantyca.aivox.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.aivox.EnvConst;
import com.semantyca.aivox.service.QueueService;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import com.semantyca.mixpla.dto.queue.metric.MetricEventDTO;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
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

    @Inject
    QueueService queueService;

    @Inject
    MetricPublisher metricPublisher;

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
                    metricPublisher.publish(MetricEventDTO.of(
                            EnvConst.APP_ID,
                            dto.getBrandSlug(),
                            //MetricEventType.MESSAGE_RECEIVED,
                            MetricEventType.AI_TOKENS_USED,
                            Map.of("sceneId", dto.getSceneId(), "trackId", dto.getSceneId())
                    )).subscribe().with(
                            v -> LOGGER.debug("Published MESSAGE_RECEIVED metric"),
                            e -> LOGGER.error("Failed to publish MESSAGE_RECEIVED metric", e)
                    );
                    if (!ASSIGNED_BRANDS.contains(dto.getBrandSlug())) {
                        LOGGER.warn("Skipping message for unassigned brand: " + dto.getBrandSlug());
                        metricPublisher.publish(MetricEventDTO.of(
                                EnvConst.APP_ID,
                                dto.getBrandSlug(),
                                //MetricEventType.ERROR,
                                MetricEventType.AI_TOKENS_USED,
                                Map.of("error", "Unassigned brand: " + dto.getBrandSlug())
                        )).subscribe().with(
                                v -> LOGGER.debug("Published ERROR metric for unassigned brand"),
                                e -> LOGGER.error("Failed to publish ERROR metric", e)
                        );
                        return Uni.createFrom().completionStage(message.nack(
                                new RuntimeException("Unassigned brand: " + dto.getBrandSlug())
                        ));
                    }
                    return queueService.addToQueue(dto)
                            .onItem().invoke(result -> {
                                LOGGER.info("Queue request completed for sceneId: " + dto.getSceneId());
                                metricPublisher.publish(MetricEventDTO.of(
                                        EnvConst.APP_ID,
                                        dto.getBrandSlug(),
                                        //MetricEventType.MIX_FED,
                                        MetricEventType.AI_TOKENS_USED,
                                        Map.of("sceneId", dto.getSceneId(), "trackId", dto.getSceneId())
                                )).subscribe().with(
                                        v -> LOGGER.debug("Published MIX_FED metric"),
                                        e -> LOGGER.error("Failed to publish MIX_FED metric", e)
                                );
                            })
                            .replaceWithVoid()
                            .onItem().transformToUni(v -> Uni.createFrom().completionStage(message.ack()));
                })
                .onFailure().recoverWithUni(e -> {
                    LOGGER.error("Failed processing message", e);
                    metricPublisher.publish(MetricEventDTO.of(
                            EnvConst.APP_ID,
                            "unknown",
                            //MetricEventType.ERROR,
                            MetricEventType.AI_TOKENS_USED,
                            Map.of("error", e.getMessage())
                    )).subscribe().with(
                            v -> LOGGER.debug("Published ERROR metric"),
                            ex -> LOGGER.error("Failed to publish ERROR metric", ex)
                    );
                    return Uni.createFrom().completionStage(message.nack(e));
                });
    }
}
