package com.semantyca.aivox.rest;

import com.semantyca.aivox.streaming.HlsSegment;
import com.semantyca.aivox.streaming.StreamManager;
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
    
    @Inject StreamManager streamManager;
    
    public void setupRoutes(Router router) {
        String path = "/api/stream";
        
        router.route(HttpMethod.GET, path + "/:brand/master.m3u8").handler(this::getMasterPlaylist);
        router.route(HttpMethod.GET, path + "/:brand/stream.m3u8").handler(this::getPlaylist);
        router.route(HttpMethod.GET, path + "/:brand/segments/:segmentFile").handler(this::getSegment);
    }
    
    private void getMasterPlaylist(RoutingContext rc) {
        String brand = rc.pathParam("brand").toLowerCase();
        
        try {
            String playlist = streamManager.generateMasterPlaylist(brand);
            rc.response()
                .putHeader("Content-Type", "application/vnd.apple.mpegurl")
                .putHeader("Cache-Control", "no-cache")
                .end(playlist);
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
            // Use default bitrate if not specified
            if (bitrate == null) {
                bitrate = 128000L; // Default to 128k
            }
            
            String playlist = streamManager.generatePlaylist(brand, bitrate);
            rc.response()
                .putHeader("Content-Type", "application/vnd.apple.mpegurl")
                .putHeader("Cache-Control", "no-cache")
                .end(playlist);
        } catch (Exception e) {
            LOGGER.error("Failed to generate playlist for brand: " + brand + ", bitrate: " + bitrate, e);
            rc.response().setStatusCode(404).end("Stream not found");
        }
    }
    
    private void getSegment(RoutingContext rc) {
        String segmentFile = rc.pathParam("segmentFile");
        String brand = rc.pathParam("brand").toLowerCase();
        
        try {
            HlsSegment segment = streamManager.getSegment(brand, segmentFile);
            
            if (segment == null) {
                throw new WebApplicationException(Response.Status.NOT_FOUND);
            }
            
            rc.response()
                .putHeader("Content-Type", "video/MP2T")
                .putHeader("Cache-Control", "no-cache")
                .end(Buffer.buffer(segment.getData()));
        } catch (WebApplicationException e) {
            rc.response().setStatusCode(e.getResponse().getStatus()).end("Segment not found");
        } catch (Exception e) {
            LOGGER.error("Failed to get segment: " + segmentFile + " for brand: " + brand, e);
            rc.response().setStatusCode(500).end("Error serving segment");
        }
    }
}
