package com.semantyca.aivox.streaming;

import com.semantyca.aivox.service.playlist.PlaylistManager;
import lombok.Getter;
import org.jboss.logging.Logger;

@Getter
public class RadioStationBundle {
    private final String brand;
    private final StreamManager streamManager;
    private final PlaylistManager playlistManager;
    private volatile boolean active;
    private final long createdAt;
    private static final Logger LOGGER = Logger.getLogger(RadioStationBundle.class);

    public RadioStationBundle(String brand, StreamManager streamManager, PlaylistManager playlistManager) {
        this.brand = brand;
        this.streamManager = streamManager;
        this.playlistManager = playlistManager;
        this.createdAt = System.currentTimeMillis();
        this.active = true;
    }

    public void shutdown() {
        active = false;
        try {
            if (streamManager != null) {
                streamManager.shutdown();
            }
            if (playlistManager != null) {
                playlistManager.shutdown();
            }
            LOGGER.info("Station bundle shutdown completed for brand: " + brand);
        } catch (Exception e) {
            LOGGER.error("Error during shutdown of station bundle " + brand + ": " + e.getMessage(), e);
        }
    }
}
