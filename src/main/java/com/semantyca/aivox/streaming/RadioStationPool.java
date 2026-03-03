package com.semantyca.aivox.streaming;

import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.config.HlsConfig;
import com.semantyca.aivox.model.stream.RadioStream;
import com.semantyca.aivox.repository.soundfragment.SoundFragmentFileHandler;
import com.semantyca.aivox.service.BrandService;
import com.semantyca.aivox.service.SoundFragmentBrandService;
import com.semantyca.aivox.service.manipulation.AudioSegmentationService;
import com.semantyca.aivox.service.playlist.PlaylistManager;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Startup
@ApplicationScoped
public class RadioStationPool {
    private static final Logger LOGGER = Logger.getLogger(RadioStationPool.class);

    private final ConcurrentHashMap<String, RadioStream> pool = new ConcurrentHashMap<>();

    private final AivoxConfig aivoxConfig;
    private final HlsConfig hlsConfig;
    private final WaitingAudioProvider waitingAudioProvider;
    private final SegmentFeederTimer segmentFeederTimer;
    private final SliderTimer sliderTimer;
    private final SoundFragmentBrandService soundFragmentBrandService;
    private final BrandService brandService;
    private final SoundFragmentFileHandler fileHandler;
    private final AudioSegmentationService segmentationService;
    private final Vertx vertx;

    @Inject
    public RadioStationPool(AivoxConfig aivoxConfig, HlsConfig hlsConfig, WaitingAudioProvider waitingAudioProvider,
                            SegmentFeederTimer segmentFeederTimer, SliderTimer sliderTimer,
                            SoundFragmentBrandService soundFragmentBrandService, BrandService brandService,
                            SoundFragmentFileHandler fileHandler, AudioSegmentationService segmentationService, Vertx vertx) {
        this.aivoxConfig = aivoxConfig;
        this.hlsConfig = hlsConfig;
        this.waitingAudioProvider = waitingAudioProvider;
        this.segmentFeederTimer = segmentFeederTimer;
        this.sliderTimer = sliderTimer;
        this.soundFragmentBrandService = soundFragmentBrandService;
        this.brandService = brandService;
        this.fileHandler = fileHandler;
        this.segmentationService = segmentationService;
        this.vertx = vertx;
    }

    @PostConstruct
    void initStationsFromWhitelist() {
        List<String> whitelist = aivoxConfig.stationWhitelist().orElse(List.of());
        LOGGER.infof("%s Initializing stations from whitelist: %s", logPrefix(), whitelist);
        if (aivoxConfig.stationWhitelist().isPresent()) {
            for (String brandName : whitelist) {
                initializeStation(brandName)
                        .subscribe()
                        .with(
                                bundle -> LOGGER.infof("%s Station initialized for brand: %s", logPrefix(brandName), brandName),
                                failure -> LOGGER.errorf("%s Failed to initialize station for brand: %s", logPrefix(brandName), brandName, failure)
                        );
            }
        }
    }

    public Uni<RadioStream> initializeStation(String brandName) {
        LOGGER.infof("%s Attempting to initialize station for brand: %s", logPrefix(brandName), brandName);

        return brandService.getBySlugName(brandName)
                .onItem().transformToUni(brand -> {
                    if (brand == null) {
                        LOGGER.errorf("%s Brand not found for slug: %s", logPrefix(brandName), brandName);
                        return Uni.createFrom().failure(new IllegalArgumentException("Brand not found: " + brandName));
                    }

                    RadioStream existingStream = pool.get(brandName);
                    if (existingStream != null && existingStream.isActive()) {
                        LOGGER.infof("%s Station already active, returning existing instance", logPrefix(brandName));
                        return Uni.createFrom().item(existingStream);
                    }

                    RadioStream radioStream = pool.computeIfAbsent(brandName, key -> {
                        LOGGER.infof("%s Creating new stream for brand", logPrefix(key));
                        PlaylistManager playlistManager = new PlaylistManager(key, brand.getId(), aivoxConfig, vertx, waitingAudioProvider,
                                soundFragmentBrandService, brandService, fileHandler, segmentationService);
                        StreamManager streamManager = new StreamManager(key, playlistManager, hlsConfig, segmentFeederTimer, sliderTimer);
                        streamManager.initializeStream();
                        return new RadioStream(brand, streamManager, playlistManager);
                    });

                    LOGGER.infof("%s Station stream ready (lazy initialization will occur on first use)", logPrefix(brandName));
                    return Uni.createFrom().item(radioStream);
                })
                .onFailure().invoke(failure ->
                        LOGGER.errorf("%s Failed to initialize station: %s", logPrefix(brandName), failure.getMessage(), failure)
                );
    }

    public Uni<RadioStream> get(String brandName) {
        RadioStream stream = pool.get(brandName);
        return Uni.createFrom().item(stream);
    }

    public Uni<RadioStream> getStation(String brandName) {
        RadioStream stream = pool.get(brandName);
        return Uni.createFrom().item(stream);
    }

    public Uni<RadioStream> stopAndRemoveStation(String brandName) {
        LOGGER.infof("%s Attempting to stop and remove station", logPrefix(brandName));

        RadioStream stream = pool.remove(brandName);

        if (stream != null) {
            LOGGER.infof("%s Station found in pool and removed, shutting down", logPrefix(brandName));
            stream.shutdown();
            return Uni.createFrom().item(stream);
        } else {
            LOGGER.warnf("%s Station not found in pool during stopAndRemove", logPrefix(brandName));
            return Uni.createFrom().nullItem();
        }
    }

    private String logPrefix() {
        return "[RadioStationPool]";
    }

    private String logPrefix(String brand) {
        return "[" + brand + "]";
    }

    public Collection<RadioStream> getActiveStations() {
        return new ArrayList<>(pool.values());
    }

}
