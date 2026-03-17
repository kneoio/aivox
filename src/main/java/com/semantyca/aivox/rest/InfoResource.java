package com.semantyca.aivox.rest;

import com.semantyca.aivox.service.BrandService;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

@ApplicationScoped
public class InfoResource {
    
    private static final Logger LOGGER = Logger.getLogger(InfoResource.class);
    private static final String[] SUPPORTED_MIXPLA_VERSIONS = {"2.5.5","2.5.6","2.5.7","2.5.8","2.5.9"};

    @Inject
    private BrandService brandService;
    
    public void setupRoutes(Router router) {
        String path = "/aivox/info";
        router.route(HttpMethod.GET, path + "/all-brands").handler(this::validateMixplaAccess).handler(this::getAllBrands);
    }

    
    private void getAllBrands(RoutingContext rc) {
        String country = rc.request().getParam("country");
        String query = rc.request().getParam("query");
        String limitParam = rc.request().getParam("limit");
        String offsetParam = rc.request().getParam("offset");
        
        int limit = limitParam != null ? Integer.parseInt(limitParam) : 100;
        int offset = offsetParam != null ? Integer.parseInt(offsetParam) : 0;
        
        brandService.getAllDTO(limit, offset, null, country, query)
            .subscribe()
            .with(
                brands -> {
                    rc.response()
                        .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                        .putHeader("Access-Control-Allow-Origin", "*")
                        .end(Json.encode(brands));
                },
                failure -> {
                    LOGGER.error("Failed to get all brands", failure);
                    rc.response().setStatusCode(500).end("Failed to get all brands");
                }
            );
    }

    private void validateMixplaAccess(RoutingContext rc) {
        String host = rc.request().remoteAddress().host();
        String clientId = rc.request().getHeader("X-Client-ID");
        String mixplaApp = rc.request().getHeader("X-Mixpla-App");
        
        LOGGER.info("Access validation - Host: " + host + ", Client-ID: " + clientId + ", Mixpla-App: " + mixplaApp);

        if (mixplaApp != null && isValidMixplaApp(mixplaApp)) { 
            LOGGER.info("Allowing valid Mixpla app access");
            rc.next(); 
            return; 
        }
        if ("mixpla-web".equals(clientId)) { 
            LOGGER.info("Allowing Mixpla web access");
            rc.next(); 
            return; 
        }

        LOGGER.warn("Access denied for host: " + host);
        rc.response().setStatusCode(403).end("Access denied");
    }

    private boolean isValidMixplaApp(String mixplaApp) {
        final String prefix = "mixpla-mobile";
        if (!mixplaApp.startsWith(prefix)) return false;

        String version = mixplaApp.substring(prefix.length()).replaceFirst("^[^0-9]*", "");
        for (String v : SUPPORTED_MIXPLA_VERSIONS) if (v.equals(version)) return true;
        return false;
    }
}
