package com.semantyca.aivox.service;

import com.semantyca.aivox.streaming.LiveSoundFragment;
import com.semantyca.aivox.streaming.RadioStationPool;
import com.semantyca.aivox.streaming.RadioStationBundle;
import com.semantyca.aivox.streaming.StreamManager;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class StreamingService {
    private static final Logger LOGGER = Logger.getLogger(StreamingService.class);

    @Inject
    RadioStationPool radioStationPool;

    public Uni<RadioStationBundle> initializeStation(String brand) {
        return radioStationPool.initializeStation(brand)
                .onFailure().invoke(failure -> 
                    LOGGER.error("Failed to initialize station for brand: " + brand, failure)
                );
    }

    public Uni<RadioStationBundle> stopStation(String brand) {
        return radioStationPool.stopAndRemoveStation(brand)
                .onFailure().invoke(failure -> 
                    LOGGER.error("Failed to stop station for brand: " + brand, failure)
                );
    }

    public Uni<StreamManager> getStreamManager(String brand) {
        return radioStationPool.getStation(brand)
                .onItem().ifNull().failWith(() -> 
                    new RuntimeException("Station not active for brand: " + brand)
                )
                .onItem().transform(RadioStationBundle::getStreamManager)
                .onItem().ifNull().failWith(() -> 
                    new RuntimeException("Stream manager not available for brand: " + brand)
                );
    }

    public Uni<RadioStationBundle> getStation(String brand) {
        return radioStationPool.getStation(brand)
                .onItem().ifNull().failWith(() -> 
                    new RuntimeException("Station not found for brand: " + brand)
                );
    }

    public Uni<List<RadioStationBundle>> getActiveStations() {
        return Uni.createFrom().item(new ArrayList<>(radioStationPool.getActiveStations()));
    }

    public Uni<List<String>> getActiveStationNames() {
        return Uni.createFrom().item(new ArrayList<>(radioStationPool.getActiveStationNames()));
    }

    public Uni<String> getMasterPlaylist(String brand) {
        return getStreamManager(brand)
                .onItem().transform(streamManager -> streamManager.generateMasterPlaylist(brand));
    }

    public Uni<String> getStreamPlaylist(String brand, Long bitrate) {
        return getStreamManager(brand)
                .onItem().transform(streamManager -> streamManager.generatePlaylist(brand, bitrate));
    }

    public Uni<byte[]> getSegment(String brand, String segmentFile) {
        return getStreamManager(brand)
                .onItem().transform(streamManager -> {
                    var segment = streamManager.getSegment(brand, segmentFile);
                    return segment != null ? segment.getData() : null;
                })
                .onItem().ifNull().failWith(() -> 
                    new RuntimeException("Segment not found: " + segmentFile)
                );
    }

    public Uni<Boolean> addFragment(String brand, LiveSoundFragment fragment, int priority) {
        return getStation(brand)
                .onItem().transform(bundle -> 
                    bundle.getPlaylistManager().addFragment(brand, fragment, priority)
                )
                .onItem().transform(uni -> {
                    // This is a Uni<Uni<Boolean>>, so we need to flatten it
                    return uni;
                })
                .onItem().transformToUni(uni -> uni);
    }

    public Uni<RadioStationBundle> initializeStream(String brand) {
        return initializeStation(brand);
    }

    public Uni<RadioStationBundle> shutdownStream(String brand) {
        return stopStation(brand);
    }
}
