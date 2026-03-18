package com.semantyca.aivox.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.service.CommandService;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CommandResource {
    
    private static final Logger LOGGER = Logger.getLogger(CommandResource.class);
    private static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    
    @Inject
    AivoxConfig config;
    
    @Inject
    CommandService commandService;
    
    public void setupRoutes(Router router) {
        String path = "/aivox";
        router.route(HttpMethod.POST, path + "/:brand/command").handler(this::validateAccess).handler(this::createStream);
        router.route(HttpMethod.DELETE, path + "/:brand/command").handler(this::validateAccess).handler(this::stopStream);
    }
    
    private void createStream(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        
        commandService.initializeStream(brand)
            .subscribe()
            .with(
                response -> {
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
                    LOGGER.error("Failed to create stream for brand: " + brand, failure);
                    rc.response()
                        .setStatusCode(500)
                        .putHeader("Content-Type", "application/json")
                        .end("{\"error\": \"Failed to create stream: " + failure.getMessage() + "\"}");
                }
            );
    }
    
    private void stopStream(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        
        commandService.stopStream(brand)
            .subscribe()
            .with(
                response -> {
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
                    LOGGER.error("Failed to stop stream for brand: " + brand, failure);
                    rc.response()
                        .setStatusCode(500)
                        .putHeader("Content-Type", "application/json")
                        .end("{\"error\": \"Failed to stop stream: " + failure.getMessage() + "\"}");
                }
            );
    }

    private void validateAccess(RoutingContext rc) {
        String host = rc.request().remoteAddress().host();
        String clientId = rc.request().getHeader("X-Client-ID");
        String mixplaApp = rc.request().getHeader("X-Mixpla-App");
        String debugToken = rc.request().getHeader("X-Debug-Token");
        
        LOGGER.info("Debug access validation - Host: " + host + ", Client-ID: " + clientId + ", Mixpla-App: " + mixplaApp + ", Debug-Token: " + (debugToken != null ? "provided" : "missing"));

        if (debugToken != null && debugToken.equals(config.debugToken())) {
            LOGGER.info("Allowing access via valid debug token");
            rc.next();
            return;
        }

        if ("127.0.0.1".equals(host) || "::1".equals(host)) {
            LOGGER.info("Allowing localhost access");
            rc.next();
            return;
        }

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
}
