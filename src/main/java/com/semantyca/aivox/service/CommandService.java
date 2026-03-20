package com.semantyca.aivox.service;

import com.semantyca.aivox.EnvConst;
import com.semantyca.aivox.agent.AgentClient;
import com.semantyca.aivox.messaging.MetricPublisher;
import com.semantyca.mixpla.dto.queue.metric.MetricEventDTO;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class CommandService {

    private static final Logger LOGGER = Logger.getLogger(CommandService.class);

    @Inject
    StreamingService streamingService;

    @Inject
    MetricPublisher metricPublisher;

    @Inject
    AgentClient agentClient;

    public Uni<Map<String, Object>> initializeStream(String brand) {
        return streamingService.initializeStation(brand)
                .call(bundle -> agentClient.sendJesoosCommand(brand, "start")
                        .invoke(() -> LOGGER.infof("Sent Jesoos start command for brand: %s", brand))
                        .onFailure().invoke(failure -> LOGGER.errorf(failure, "Failed to send Jesoos start command for brand: %s", brand))
                        .onFailure().recoverWithItem(() -> null)
                )
                .invoke(bundle -> LOGGER.infof("Stream initialized for brand: %s", brand))
                .onFailure().invoke(failure -> {
                    publishStationMetric(brand, MetricEventType.FATAL_ERROR, "init_stream", Map.of(
                            "brand", brand,
                            "error", failure.getMessage()
                    ));
                    LOGGER.errorf("Failed to create stream for brand: %s", brand, failure);
                })
                .map(bundle -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("brand", brand);
                    response.put("status", "initialized");
                    response.put("active", bundle.isActive());
                    response.put("createdAt", bundle.getCreatedAt());
                    return response;
                });
    }

    public Uni<Map<String, Object>> stopStream(String brand) {
        return streamingService.stopStation(brand)
                .call(bundle -> agentClient.sendJesoosCommand(brand, "stop")
                        .invoke(() -> LOGGER.infof("Sent Jesoos stop command for brand: %s", brand))
                        .onFailure().invoke(failure -> LOGGER.errorf(failure, "Failed to send Jesoos stop command for brand: %s", brand))
                        .onFailure().recoverWithItem(() -> null)
                )
                .invoke(bundle -> {
                    publishStationMetric(brand, MetricEventType.INFORMATION, "stop_stream", Map.of(
                            "brand", brand,
                            "status", "stopped",
                            "source", "debug_api"
                    ));
                    LOGGER.infof("Stream stopped for brand: %s", brand);
                })
                .onFailure().invoke(failure -> {
                    publishStationMetric(brand, MetricEventType.ERROR, "stop_stream", Map.of(
                            "brand", brand,
                            "error", failure.getMessage()
                    ));
                    LOGGER.errorf("Failed to stop stream for brand: %s", brand, failure);
                })
                .map(bundle -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("brand", brand);
                    response.put("status", "stopped");
                    return response;
                });
    }

    private void publishStationMetric(String brand, MetricEventType eventType, String code, Map<String, Object> payload) {
        try {
            MetricEventDTO event = MetricEventDTO.of(
                    EnvConst.APP_ID,
                    brand,
                    eventType,
                    UUID.randomUUID(),
                    code,
                    payload
            );

            metricPublisher.publish(event)
                    .subscribe()
                    .with(
                            success -> LOGGER.infof("Published %s metric for brand %s", eventType, brand),
                            failure -> LOGGER.errorf("Failed to publish %s metric for brand %s", eventType, brand, failure)
                    );
        } catch (Exception e) {
            LOGGER.errorf("Failed to create metric event %s for brand %s", eventType, brand, e);
        }
    }
}
