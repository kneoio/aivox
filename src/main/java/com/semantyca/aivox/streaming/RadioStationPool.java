package com.semantyca.aivox.streaming;

import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.config.HlsConfig;
import com.semantyca.aivox.repository.brand.BrandRepository;
import com.semantyca.aivox.repository.soundfragment.SoundFragmentFileHandler;
import com.semantyca.aivox.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.aivox.service.RadioDJProcessor;
import com.semantyca.aivox.service.manipulation.AudioSegmentationService;
import com.semantyca.aivox.service.playlist.PlaylistManager;
import io.smallrye.mutiny.Uni;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Startup
@ApplicationScoped
public class RadioStationPool {
    private static final Logger LOGGER = Logger.getLogger(RadioStationPool.class);

    private final ConcurrentHashMap<String, RadioStationBundle> pool = new ConcurrentHashMap<>();

    private final AivoxConfig aivoxConfig;
    private final HlsConfig hlsConfig;
    private final RadioDJProcessor radioDJProcessor;
    private final WaitingAudioProvider waitingAudioProvider;
    private final SegmentFeederTimer segmentFeederTimer;
    private final SliderTimer sliderTimer;
    private final SoundFragmentRepository soundFragmentRepository;
    private final BrandRepository brandRepository;
    private final SoundFragmentFileHandler fileHandler;
    private final AudioSegmentationService segmentationService;

    @Inject
    public RadioStationPool(AivoxConfig aivoxConfig, HlsConfig hlsConfig,
                            RadioDJProcessor radioDJProcessor, WaitingAudioProvider waitingAudioProvider,
                            SegmentFeederTimer segmentFeederTimer, SliderTimer sliderTimer,
                            SoundFragmentRepository soundFragmentRepository, BrandRepository brandRepository,
                            SoundFragmentFileHandler fileHandler, AudioSegmentationService segmentationService) {
        this.aivoxConfig = aivoxConfig;
        this.hlsConfig = hlsConfig;
        this.radioDJProcessor = radioDJProcessor;
        this.waitingAudioProvider = waitingAudioProvider;
        this.segmentFeederTimer = segmentFeederTimer;
        this.sliderTimer = sliderTimer;
        this.soundFragmentRepository = soundFragmentRepository;
        this.brandRepository = brandRepository;
        this.fileHandler = fileHandler;
        this.segmentationService = segmentationService;
    }

    @PostConstruct
    void initStationsFromWhitelist() {
        List<String> whitelist = aivoxConfig.getStationWhitelist().orElse(List.of());
        LOGGER.info("Initializing stations from whitelist: " + whitelist);
        if (aivoxConfig.getStationWhitelist().isPresent()) {
            for (String brandName : whitelist) {
                initializeStation(brandName)
                        .subscribe()
                        .with(
                                bundle -> LOGGER.info("Station initialized for brand: " + brandName),
                                failure -> LOGGER.error("Failed to initialize station for brand: " + brandName, failure)
                        );
            }
        }

    }

    public Uni<RadioStationBundle> initializeStation(String brandName) {
        LOGGER.info("Attempting to initialize station for brand: " + brandName);

        return Uni.createFrom().item(brandName)
                .onItem().transformToUni(brand -> {
                    RadioStationBundle existingBundle = pool.get(brand);
                    if (existingBundle != null && existingBundle.isActive()) {
                        LOGGER.info("Station " + brand + " already active. Returning existing instance.");
                        return Uni.createFrom().item(existingBundle);
                    }

                    pool.computeIfAbsent(brand, key -> {
                        LOGGER.info("Creating new bundle for brand: " + key);
                        PlaylistManager playlistManager = new PlaylistManager(aivoxConfig, hlsConfig, radioDJProcessor, waitingAudioProvider, soundFragmentRepository, brandRepository, fileHandler, segmentationService);
                        StreamManager streamManager = new StreamManager(playlistManager, hlsConfig, segmentFeederTimer, sliderTimer);
                        streamManager.initializeStream(key);
                        return new RadioStationBundle(key, streamManager, playlistManager);
                    });

                    RadioStationBundle bundle = pool.get(brand);

                    return bundle.getPlaylistManager().initializeBrand(brand)
                            .replaceWith(bundle);
                })
                .onFailure().invoke(failure ->
                        LOGGER.error("Failed to initialize station " + brandName + ": " + failure.getMessage(), failure)
                );
    }

    public Uni<RadioStationBundle> getStation(String brandName) {
        RadioStationBundle bundle = pool.get(brandName);
        return Uni.createFrom().item(bundle);
    }

    public Uni<RadioStationBundle> stopAndRemoveStation(String brandName) {
        LOGGER.info("Attempting to stop and remove station: " + brandName);

        RadioStationBundle bundle = pool.remove(brandName);

        if (bundle != null) {
            LOGGER.info("Station " + brandName + " found in pool and removed. Shutting down components.");

            // Shutdown both components
            bundle.shutdown();

            return Uni.createFrom().item(bundle);
        } else {
            LOGGER.warn("Station " + brandName + " not found in pool during stopAndRemove.");
            return Uni.createFrom().nullItem();
        }
    }

    public Collection<RadioStationBundle> getActiveStations() {
        return new ArrayList<>(pool.values());
    }

    public Set<String> getActiveStationNames() {
        return new HashSet<>(pool.keySet());
    }

    public boolean isStationActive(String brandName) {
        RadioStationBundle bundle = pool.get(brandName);
        return bundle != null && bundle.isActive();
    }

    public int getActiveStationCount() {
        return (int) pool.values().stream()
                .filter(RadioStationBundle::isActive)
                .count();
    }

}
