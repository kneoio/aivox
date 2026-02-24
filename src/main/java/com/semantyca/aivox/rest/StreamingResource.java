package com.semantyca.aivox.rest;

import com.semantyca.aivox.service.StreamingService;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@ApplicationScoped
public class StreamingResource {
    
    private static final Logger LOGGER = Logger.getLogger(StreamingResource.class);
    
    @Inject 
    private StreamingService streamingService;
    
    public void setupRoutes(Router router) {
        String path = "/api/stream";
        
        router.route(HttpMethod.GET, path + "/:brand/master.m3u8").handler(this::getMasterPlaylist);
        router.route(HttpMethod.GET, path + "/:brand/stream.m3u8").handler(this::getPlaylist);
        router.route(HttpMethod.GET, path + "/:brand/segments/:segmentFile").handler(this::getSegment);
    }
    
    private void getMasterPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        
        try {
            streamingService.getMasterPlaylist(brand)
                .onItem().transform(playlist -> {
                    rc.response()
                        .putHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .putHeader("Cache-Control", "no-cache")
                        .end(playlist);
                    return playlist;
                })
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to generate master playlist for brand: " + brand, failure);
                    rc.response().setStatusCode(404).end("Stream not found");
                })
                .subscribe();
                
        } catch (Exception e) {
            LOGGER.error("Failed to generate master playlist for brand: " + brand, e);
            rc.response().setStatusCode(404).end("Stream not found");
        }
    }
    
    private void getPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        Long bitrate = rc.request().getParam("bitrate") != null ? 
            Long.parseLong(rc.request().getParam("bitrate")) : null;
        
        try {
            streamingService.getStreamPlaylist(brand, bitrate)
                .onItem().transform(playlist -> {
                    rc.response()
                        .putHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .putHeader("Cache-Control", "no-cache")
                        .end(playlist);
                    return playlist;
                })
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to generate playlist for brand: " + brand + ", bitrate: " + bitrate, failure);
                    rc.response().setStatusCode(404).end("Stream not found");
                })
                .subscribe();
                
        } catch (Exception e) {
            LOGGER.error("Failed to generate playlist for brand: " + brand + ", bitrate: " + bitrate, e);
            rc.response().setStatusCode(404).end("Stream not found");
        }
    }
    
    private void getSegment(RoutingContext rc) {
        String segmentFile = rc.pathParam("segmentFile");
        String brand = rc.pathParam("brand").toLowerCase();
        
        try {
            streamingService.getSegment(brand, segmentFile)
                .onItem().transform(segmentData -> {
                    if (segmentData == null) {
                        throw new WebApplicationException(Response.Status.NOT_FOUND);
                    }
                    
                    rc.response()
                        .putHeader("Content-Type", "video/MP2T")
                        .putHeader("Cache-Control", "no-cache")
                        .end(Buffer.buffer(segmentData));
                    return segmentData;
                })
                .onFailure().invoke(failure -> {
                    LOGGER.error("Failed to get segment: " + segmentFile + " for brand: " + brand, failure);
                    if (failure instanceof WebApplicationException) {
                        WebApplicationException wae = (WebApplicationException) failure;
                        rc.response().setStatusCode(wae.getResponse().getStatus()).end("Segment not found");
                    } else {
                        rc.response().setStatusCode(500).end("Error serving segment");
                    }
                })
                .subscribe();
                
        } catch (WebApplicationException e) {
            rc.response().setStatusCode(e.getResponse().getStatus()).end("Segment not found");
        } catch (Exception e) {
            LOGGER.error("Failed to get segment: " + segmentFile + " for brand: " + brand, e);
            rc.response().setStatusCode(500).end("Error serving segment");
        }
    }
}
