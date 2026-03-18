package com.semantyca.aivox.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.messaging.MetricPublisher;
import com.semantyca.aivox.service.StreamingService;
import com.semantyca.mixpla.dto.queue.metric.MetricEventDTO;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class DebugResource {
    
    private static final Logger LOGGER = Logger.getLogger(DebugResource.class);
    private static final String[] SUPPORTED_MIXPLA_VERSIONS = {"2.5.5","2.5.6","2.5.7","2.5.8","2.5.9"};
    private static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    
    @Inject
    StreamingService streamingService;

    
    @Inject
    AivoxConfig config;
    
    @Inject
    MetricPublisher metricPublisher;
    
    public void setupRoutes(Router router) {
        String path = "/aivox/debug";
        router.route(HttpMethod.GET, path + "/streams").handler(this::validateDebugAccess).handler(this::listStreams);
        router.route(HttpMethod.POST, path + "/queue/:brand").handler(this::validateDebugAccess).handler(this::testAddToQueue);
    }
    

    private void listStreams(RoutingContext rc) {
        streamingService.getActiveStations()
            .subscribe()
            .with(
                stations -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("activeStations", stations.size());

                    stations.forEach(bundle -> {
                        Map<String, Object> stationInfo = new HashMap<>();
                        stationInfo.put("brand", bundle.getSlugName());
                        stationInfo.put("active", bundle.isActive());
                        stationInfo.put("createdAt", bundle.getCreatedAt());
                    });

                    try {
                        String jsonResponse = objectMapper.writeValueAsString(response);
                        rc.response()
                            .putHeader("Content-Type", "application/json")
                            .end(jsonResponse);
                    } catch (Exception e) {
                        LOGGER.error("Failed to serialize JSON response", e);
                        rc.response()
                            .setStatusCode(500)
                            .putHeader("Content-Type", "application/json")
                            .end("{\"error\": \"Internal server error\"}");
                    }
                },
                failure -> {
                    LOGGER.error("Failed to list streams", failure);
                    rc.response()
                        .setStatusCode(500)
                        .putHeader("Content-Type", "application/json")
                        .end("{\"error\": \"Failed to list streams: " + failure.getMessage() + "\"}");
                }
            );
    }

    private void testAddToQueue(RoutingContext rc) {
        rc.response()
            .putHeader("Content-Type", MediaType.APPLICATION_JSON)
            .end("{\"message\": \"Queue test endpoint\"}");
    }

    private void validateDebugAccess(RoutingContext rc) {
        String host = rc.request().remoteAddress().host();
        String clientId = rc.request().getHeader("X-Client-ID");
        String mixplaApp = rc.request().getHeader("X-Mixpla-App");
        String debugToken = rc.request().getHeader("X-Debug-Token");
        
        LOGGER.info("Debug access validation - Host: " + host + ", Client-ID: " + clientId + ", Mixpla-App: " + mixplaApp + ", Debug-Token: " + (debugToken != null ? "provided" : "missing"));
        
        // Check debug token first
        if (debugToken != null && debugToken.equals(config.debugToken())) {
            LOGGER.info("Allowing access via valid debug token");
            rc.next();
            return;
        }
        
        // Allow localhost access
        if ("127.0.0.1".equals(host) || "::1".equals(host)) {
            LOGGER.info("Allowing localhost access");
            rc.next();
            return;
        }
        
        // Check Mixpla app validation
        if (mixplaApp != null && isValidMixplaApp(mixplaApp)) {
            LOGGER.info("Allowing valid Mixpla app access");
            rc.next();
            return;
        }
        
        // Check Mixpla web client
        if ("mixpla-web".equals(clientId)) {
            LOGGER.info("Allowing Mixpla web access");
            rc.next();
            return;
        }

        LOGGER.warn("Debug access denied for host: " + host);
        rc.response()
            .setStatusCode(403)
            .putHeader("Content-Type", MediaType.APPLICATION_JSON)
            .end("{\"error\": \"Access denied - valid debug token or Mixpla headers required\"}");
    }

    private boolean isValidMixplaApp(String mixplaApp) {
        final String prefix = "mixpla-mobile";
        if (!mixplaApp.startsWith(prefix)) return false;

        String version = mixplaApp.substring(prefix.length()).replaceFirst("^[^0-9]*", "");
        for (String v : SUPPORTED_MIXPLA_VERSIONS) if (v.equals(version)) return true;
        return false;
    }

    private void publishStationMetric(String brand, MetricEventType eventType, Map<String, Object> payload) {
        try {
            MetricEventDTO event = MetricEventDTO.of(
                "debug-api",
                brand,
                eventType,
                UUID.randomUUID(),
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
