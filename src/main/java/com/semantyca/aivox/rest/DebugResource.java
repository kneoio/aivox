package com.semantyca.aivox.rest;

import com.semantyca.aivox.service.StreamingService;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class DebugResource {
    
    private static final Logger LOGGER = Logger.getLogger(DebugResource.class);
    
    @Inject
    StreamingService streamingService;
    
    public void setupRoutes(Router router) {
        String path = "/api/debug";
        
        // Create/initialize a stream
        router.route(HttpMethod.POST, path + "/stream/:brand").handler(this::createStream);
        
        // Stop a stream
        router.route(HttpMethod.DELETE, path + "/stream/:brand").handler(this::stopStream);
        
        // List all active streams
        router.route(HttpMethod.GET, path + "/streams").handler(this::listStreams);
    }
    
    private void createStream(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        
        streamingService.initializeStation(brand)
            .onItem().transform(bundle -> {
                Map<String, Object> response = new HashMap<>();
                response.put("brand", brand);
                response.put("status", "initialized");
                response.put("active", bundle.isActive());
                response.put("createdAt", bundle.getCreatedAt());
                
                rc.response()
                    .putHeader("Content-Type", "application/json")
                    .end(response.toString());
                
                LOGGER.info("Stream initialized for brand: " + brand);
                return bundle;
            })
            .onFailure().invoke(failure -> {
                LOGGER.error("Failed to create stream for brand: " + brand, failure);
                rc.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end("{\"error\": \"Failed to create stream: " + failure.getMessage() + "\"}");
            })
            .subscribe();
    }
    
    private void stopStream(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        
        streamingService.stopStation(brand)
            .onItem().transform(bundle -> {
                Map<String, Object> response = new HashMap<>();
                response.put("brand", brand);
                response.put("status", "stopped");
                
                rc.response()
                    .putHeader("Content-Type", "application/json")
                    .end(response.toString());
                
                LOGGER.info("Stream stopped for brand: " + brand);
                return bundle;
            })
            .onFailure().invoke(failure -> {
                LOGGER.error("Failed to stop stream for brand: " + brand, failure);
                rc.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end("{\"error\": \"Failed to stop stream: " + failure.getMessage() + "\"}");
            })
            .subscribe();
    }
    
    private void listStreams(RoutingContext rc) {
        streamingService.getActiveStations()
            .onItem().transform(stations -> {
                Map<String, Object> response = new HashMap<>();
                response.put("activeStations", stations.size());
                
                stations.forEach(bundle -> {
                    Map<String, Object> stationInfo = new HashMap<>();
                    stationInfo.put("brand", bundle.getBrand());
                    stationInfo.put("active", bundle.isActive());
                    stationInfo.put("createdAt", bundle.getCreatedAt());
                });
                
                rc.response()
                    .putHeader("Content-Type", "application/json")
                    .end(response.toString());
                
                return stations;
            })
            .onFailure().invoke(failure -> {
                LOGGER.error("Failed to list streams", failure);
                rc.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end("{\"error\": \"Failed to list streams: " + failure.getMessage() + "\"}");
            })
            .subscribe();
    }
}
