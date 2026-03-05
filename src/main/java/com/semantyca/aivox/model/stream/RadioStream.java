package com.semantyca.aivox.model.stream;

import com.semantyca.aivox.service.playlist.PlaylistManager;
import com.semantyca.aivox.streaming.Streamer;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.brand.ProfileOverriding;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
public class RadioStream extends AbstractStream {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStream.class);

    private Streamer streamer;
    private PlaylistManager playlistManager;
    private volatile boolean active;
    private LocalDateTime createdAt = LocalDateTime.now();

    public RadioStream(Brand brand, Streamer streamer, PlaylistManager playlistManager) {
        this.masterBrand = brand;
        this.id = brand.getId();
        this.slugName = brand.getSlugName();
        this.localizedName = new EnumMap<>(brand.getLocalizedName());
        this.timeZone = brand.getTimeZone();
        this.bitRate = brand.getBitRate();
        this.managedBy = brand.getManagedBy();
        this.createdAt = LocalDateTime.now();
        this.popularityRate = brand.getPopularityRate();
        this.timeZone = brand.getTimeZone();
        this.color = brand.getColor();
        this.bitRate = brand.getBitRate();
        this.country = brand.getCountry();
        this.scripts = brand.getScripts();
        this.streamer = streamer;
        this.playlistManager = playlistManager;
        this.active = true;
    }

    public void shutdown() {
        active = false;
        try {
            if (streamer != null) {
                streamer.shutdown();
            }
            if (playlistManager != null) {
                playlistManager.shutdown();
            }
            LOGGER.info("RadioStream shutdown completed for brand: {}", slugName);
        } catch (Exception e) {
            LOGGER.error("Error during shutdown of RadioStream " + slugName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return String.format("RadioStream[id: %s, slug: %s, baseBrand: %s]", id, slugName, masterBrand.getSlugName());
    }

    @Override
    public UUID getProfileId() {
        return null;
    }

    @Override
    public ProfileOverriding getProfileOverriding() {
        return null;
    }

    @Override
    public void clearSceneState(UUID activeSceneId) {

    }
}
