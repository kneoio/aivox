package com.semantyca.aivox.model.stream;

import com.semantyca.aivox.service.playlist.PlaylistManager;
import com.semantyca.aivox.streaming.Streamer;
import com.semantyca.mixpla.model.brand.Brand;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;

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
        this.streamer = streamer;
        this.playlistManager = playlistManager;
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
        this.aiAgentId = brand.getAiAgentId();
        this.profileId = brand.getProfileId();
        this.bitRate = brand.getBitRate();
        this.aiOverriding = brand.getAiOverriding();
        this.profileOverriding = brand.getProfileOverriding();
        this.country = brand.getCountry();
        this.scripts = brand.getScripts();
    }

    public LiveScene findActiveScene(int prepareMinutesInAdvance) {
        if (streamAgenda == null) {
            LOGGER.warn("Station '{}': No stream schedule available", slugName);
            return null;
        }

        LocalTime now = LocalTime.now(timeZone);
        LocalDateTime nowDateTime = LocalDateTime.now(timeZone);
        List<LiveScene> scenes = streamAgenda.getLiveScenes();
        
        LOGGER.debug("Station '{}': Checking {} scenes at time {}", slugName, scenes.size(), now);
        
        for (int i = 0; i < scenes.size(); i++) {
            LiveScene entry = scenes.get(i);
            LiveScene nextEntry = (i < scenes.size() - 1) ? scenes.get(i + 1) : null;
            
            LOGGER.debug("Station '{}': Checking scene '{}' with originalStartTime={}, originalEndTime={}", 
                    slugName, entry.getSceneTitle(), entry.getOriginalStartTime(), entry.getOriginalEndTime());
            
            if (entry.isOneTimeRun() && entry.getLastRunDate() != null) {
                if (entry.getLastRunDate().toLocalDate().equals(nowDateTime.toLocalDate())) {
                    LOGGER.debug("Station '{}': Skipping one-time scene '{}' - already ran today at {}",
                            slugName, entry.getSceneTitle(), entry.getLastRunDate());
                    continue;
                }
            }
            
            if (entry.isActiveAt(now, nextEntry != null ? nextEntry.getOriginalStartTime() : null)) {
                LOGGER.debug("Station '{}': Scene '{}' is active at time {}",
                        slugName, entry.getSceneTitle(), now);
                return entry;
            }
        }

        LOGGER.info("Station '{}': No active scene found at {}. Schedule may need refresh.", slugName, now);
        return null;
    }



}
