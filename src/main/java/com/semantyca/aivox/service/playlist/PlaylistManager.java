package com.semantyca.aivox.service.playlist;

import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.messaging.MetricPublisher;
import com.semantyca.aivox.repository.soundfragment.SoundFragmentFileHandler;
import com.semantyca.aivox.service.SoundFragmentBrandService;
import com.semantyca.aivox.service.manipulation.AudioSegmentationService;
import com.semantyca.aivox.streaming.LiveSoundFragment;
import com.semantyca.aivox.streaming.SongMetadata;
import com.semantyca.aivox.streaming.WaitingAudioProvider;
import com.semantyca.core.model.FileMetadata;
import com.semantyca.mixpla.dto.queue.metric.MetricEventDTO;
import com.semantyca.mixpla.dto.queue.metric.MetricEventType;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.stream.IPlaylistManager;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class PlaylistManager implements IPlaylistManager {

    private static final Logger LOGGER = Logger.getLogger(PlaylistManager.class);
    private static final int SELF_MANAGING_INTERVAL_SECONDS = 30;
    private static final int REGULAR_BUFFER_MAX = 2;
    private static final int TRIGGER_SELF_MANAGING = 2;
    private static final int PROCESSED_QUEUE_MAX_SIZE = 2;
    private static final long STARVING_FEED_COOLDOWN_MILLIS = 20_000L;

    private final ReadWriteLock slicedFragmentsLock = new ReentrantReadWriteLock();
    private final PlaylistState playlistState = new PlaylistState();
    private ScheduledExecutorService scheduler;
    private volatile long lastStarvingFeedTime = 0;
    private volatile boolean initialized = false;
    private volatile boolean initializing = false;

    private final String brand;
    private final Vertx vertx;
    private final WaitingAudioProvider waitingAudioProvider;
    private final SoundFragmentBrandService soundFragmentBrandService;
    private final SoundFragmentFileHandler fileHandler;
    private final AudioSegmentationService segmentationService;
    private final MetricPublisher metricPublisher;
    private final Path tempDir;
    private final UUID brandId;
    private final String serviceId;
    private final List<Long> bitRates;

    public PlaylistManager(String brand,
                           UUID brandId,
                           List<Long> bitRates,
                           AivoxConfig aivoxConfig,
                           Vertx vertx,
                           WaitingAudioProvider waitingAudioProvider,
                           SoundFragmentBrandService soundFragmentBrandService,
                           SoundFragmentFileHandler fileHandler,
                           AudioSegmentationService segmentationService,
                           MetricPublisher metricPublisher) {
        this.brand = brand;
        this.brandId = brandId;
        this.bitRates = bitRates;
        this.vertx = vertx;
        this.waitingAudioProvider = waitingAudioProvider;
        this.soundFragmentBrandService = soundFragmentBrandService;
        this.fileHandler = fileHandler;
        this.segmentationService = segmentationService;
        this.metricPublisher = metricPublisher;
        this.serviceId = "aivox";
        this.tempDir = Paths.get(aivoxConfig.path().temp());
        try {
            Files.createDirectories(tempDir);
            LOGGER.infof("%s Temp directory initialized: %s", logPrefix(), tempDir);
        } catch (Exception e) {
            LOGGER.errorf(e, "%s Failed to create temp directory: %s", logPrefix(), e.getMessage());
            throw new RuntimeException("Cannot initialize temp directory", e);
        }
    }

    private Uni<Void> ensureInitialized() {
        if (initialized) {
            return Uni.createFrom().voidItem();
        }
        if (initializing) {
            LOGGER.debugf("%s Already initializing, waiting...", logPrefix());
            return Uni.createFrom().voidItem();
        }
        initializing = true;
        return initialize();
    }

    public Uni<Void> initialize() {
        LOGGER.infof("%s INITIALIZING, Using brand ID: %s", logPrefix(), brandId);

        startScheduler();

        return Uni.createFrom().voidItem()
                .onItem().transformToUni(v -> {
                    waitingAudioProvider.initialize();

                    List<Uni<LiveSoundFragment>> unis = new ArrayList<>();
                    if (waitingAudioProvider.isWaitingAudioAvailable()) {
                        unis.add(waitingAudioProvider.createWaitingFragment());
                    } else {
                        LOGGER.warnf("%s Waiting audio NOT available", logPrefix());
                    }

                    if (unis.isEmpty()) {
                        initialized = true;
                        initializing = false;
                        return Uni.createFrom().voidItem();
                    }

                    return Uni.join().all(unis).andFailFast()
                            .onItem().invoke(fragments -> {
                                fragments.stream()
                                        .filter(Objects::nonNull)
                                        .forEach(playlistState.regularQueue::offer);
                                initialized = true;
                                initializing = false;
                                publishQueueMetricsSafe(null);
                            })
                            .replaceWithVoid();
                })
                .onFailure().invoke(e -> {
                    initializing = false;
                    LOGGER.errorf(e, "%s ========== INITIALIZATION FAILED ==========", logPrefix());
                });
    }

    private void startScheduler() {
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (playlistState.regularQueue.size() <= TRIGGER_SELF_MANAGING && false) {
                    int count = Math.random() < 0.5 ? 1 : 2;
                    LOGGER.infof("%s Self-managing: feeding %d frag(s)", logPrefix(), count);
                    vertx.runOnContext(() -> feedFragments(count, false)
                            .subscribe().with(
                                    v -> LOGGER.debugf("%s Scheduler feed complete", logPrefix()),
                                    e -> LOGGER.errorf(e, "%s Scheduler feed failed", logPrefix())
                            ));
                }
            } catch (Exception e) {
                LOGGER.errorf(e, "%s Error during maintenance", logPrefix());
            }
        }, 10, SELF_MANAGING_INTERVAL_SECONDS, TimeUnit.SECONDS);

    }


    private Uni<Void> feedFragments(int maxQuantity, boolean useCooldown) {
        if (useCooldown) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastStarvingFeedTime;
            if (elapsed < STARVING_FEED_COOLDOWN_MILLIS) {
                LOGGER.debugf("%s Cooldown active, %d ms remaining",
                        logPrefix(), STARVING_FEED_COOLDOWN_MILLIS - elapsed);
                return Uni.createFrom().voidItem();
            }
            lastStarvingFeedTime = now;
        }

        int remaining = Math.max(0, REGULAR_BUFFER_MAX - playlistState.regularQueue.size());
        if (remaining == 0) {
            LOGGER.debugf("%s Regular buffer at cap (%d), skipping feed", logPrefix(), REGULAR_BUFFER_MAX);
            return Uni.createFrom().voidItem();
        }

        int quantityToFetch = Math.min(remaining, maxQuantity);
        LOGGER.infof("%s Feeding %d fragment(s)", logPrefix(), quantityToFetch);

        List<UUID> excludedIds = new ArrayList<>();
        excludedIds.addAll(playlistState.regularQueue.stream()
                .map(LiveSoundFragment::getSoundFragmentId).toList());
        excludedIds.addAll(playlistState.prioritizedQueue.stream()
                .map(LiveSoundFragment::getSoundFragmentId).toList());
        slicedFragmentsLock.readLock().lock();
        try {
            excludedIds.addAll(playlistState.obtainedByHlsPlaylist.stream()
                    .map(LiveSoundFragment::getSoundFragmentId).toList());
        } finally {
            slicedFragmentsLock.readLock().unlock();
        }

        LOGGER.infof("%s Calling getBrandSongs for brandId: %s", logPrefix(), brandId);

        return soundFragmentBrandService.getBrandSongs(brandId, PlaylistItemType.SONG)
                .ifNoItem().after(Duration.ofSeconds(60)).fail()
                .onFailure().invoke(e ->
                        LOGGER.errorf(e, "%s getBrandSongs failed: %s", logPrefix(), e.getClass().getName()))
                .onFailure().recoverWithItem(Collections.emptyList())
                .onItem().invoke(songs ->
                        LOGGER.infof("%s Retrieved %d songs from database", logPrefix(), songs == null ? 0 : songs.size()))
                .onItem().transform(songs -> {
                    List<SoundFragment> available = songs.stream()
                            .filter(f -> !excludedIds.contains(f.getId()))
                            .collect(Collectors.toList());
                    //LOGGER.infof("%s After filtering: %d available songs", logPrefix(), available.size());
                    Collections.shuffle(available);
                    return available;
                })
                .onItem().transformToMulti(Multi.createFrom()::iterable)
                .select().first(quantityToFetch)
                .onItem().transformToUniAndMerge(fragment -> {
                    try {
                        return addFragmentToQueue(fragment, 10);
                    } catch (Exception e) {
                        LOGGER.warnf("%s Skipping fragment %s: %s", logPrefix(), fragment.getId(), e.getMessage());
                        return Uni.createFrom().item(false);
                    }
                })
                .collect().asList()
                .onItem().invoke(processed -> {
                    long successCount = processed.stream().filter(b -> b != null && b).count();
                    //LOGGER.infof("%s Completed: %d/%d fragments added successfully", logPrefix(), successCount, processed.size());
                })
                .replaceWithVoid();
    }

    public Uni<Boolean> addFragmentToQueue(SoundFragment soundFragment, int priority) {
        return addFragmentToQueue(soundFragment, priority, null);
    }

    public Uni<Boolean> addFragmentToQueue(SoundFragment soundFragment, int priority, UUID traceId) {
        LiveSoundFragment liveSoundFragment = new LiveSoundFragment();
        SongMetadata songMetadata = new SongMetadata(
                soundFragment.getId(),
                soundFragment.getTitle(),
                soundFragment.getArtist()
        );
        songMetadata.setTraceId(traceId);
        liveSoundFragment.setSoundFragmentId(soundFragment.getId());
        liveSoundFragment.setMetadata(songMetadata);

        LOGGER.infof("%s Processing fragment: %s - %s", logPrefix(), soundFragment.getTitle(), soundFragment.getArtist());

        // Check if fragment already has temporary file metadata (e.g., from mixing)
        /*LOGGER.infof("%s Checking fileMetadataList: null=%s, empty=%s", logPrefix(),
                soundFragment.getFileMetadataList() == null,
                soundFragment.getFileMetadataList() != null ? soundFragment.getFileMetadataList().isEmpty() : "N/A");*/
        
        if (soundFragment.getFileMetadataList() != null && !soundFragment.getFileMetadataList().isEmpty()) {
            FileMetadata existingMetadata = soundFragment.getFileMetadataList().getFirst();
            LOGGER.infof("%s Found FileMetadata, temporaryFilePath=%s", logPrefix(), existingMetadata.getTemporaryFilePath());
            if (existingMetadata.getTemporaryFilePath() != null) {
                LOGGER.infof("%s Using pre-set temporary file: %s", logPrefix(), existingMetadata.getTemporaryFilePath());
                Path tempPath = existingMetadata.getTemporaryFilePath();
                return processTempFile(tempPath, liveSoundFragment, songMetadata, priority);
            }
        }

        return fileHandler.getFirstFile(soundFragment.getId())
                .ifNoItem().after(Duration.ofSeconds(30)).fail()
                .onFailure().recoverWithUni(ex -> {
                    LOGGER.warnf("%s Failed to retrieve file metadata for %s: %s",
                            logPrefix(), soundFragment.getId(), ex.getMessage());
                    return Uni.createFrom().item((FileMetadata) null);
                })
                .onItem().transformToUni(fileMetadata -> {
                    if (fileMetadata == null) {
                        LOGGER.warnf("%s No file found for fragment: %s", logPrefix(), soundFragment.getId());
                        return Uni.createFrom().item(false);
                    }
                    LOGGER.infof("%s Materializing: %s", logPrefix(), fileMetadata.getFileOriginalName());
                    
                    // TEMP METRIC - Track file download timing
                    long downloadStartTime = System.currentTimeMillis();
                    metricPublisher.publishMetric(brand, MetricEventType.DEBUG, "file_download_started",
                            Map.of("songId", songMetadata.getSongId().toString(),
                                    "title", songMetadata.getTitle(),
                                    "artist", songMetadata.getArtist(),
                                    "fileName", fileMetadata.getFileOriginalName(),
                                    "timestamp", downloadStartTime),
                            traceId);
                    
                    return fileMetadata.materializeFileStream(tempDir.toString())
                            .ifNoItem().after(Duration.ofMinutes(5)).fail()
                            .onFailure().invoke(e -> {
                                LOGGER.errorf(e, "%s Materialization FAILED for %s", logPrefix(), fileMetadata.getFileOriginalName());
                                
                                // TEMP METRIC - Track download failure
                                long downloadEndTime = System.currentTimeMillis();
                                long downloadDuration = downloadEndTime - downloadStartTime;
                                boolean isTimeout = e instanceof java.util.concurrent.TimeoutException || 
                                                   e.getMessage() != null && e.getMessage().contains("timeout");
                                
                                metricPublisher.publishMetric(brand, MetricEventType.ERROR, "file_download_failed",
                                        Map.of("songId", songMetadata.getSongId().toString(),
                                                "title", songMetadata.getTitle(),
                                                "artist", songMetadata.getArtist(),
                                                "fileName", fileMetadata.getFileOriginalName(),
                                                "downloadDurationMs", downloadDuration,
                                                "downloadDurationSec", downloadDuration / 1000,
                                                "isTimeout", isTimeout,
                                                "errorMessage", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                                "timestamp", downloadEndTime),
                                        traceId);
                            })
                            .onItem().invoke(tempFile -> {
                                // TEMP METRIC - Track download completion and duration
                                long downloadEndTime = System.currentTimeMillis();
                                long downloadDuration = downloadEndTime - downloadStartTime;
                                metricPublisher.publishMetric(brand, MetricEventType.DEBUG, "file_download_completed",
                                        Map.of("songId", songMetadata.getSongId().toString(),
                                                "title", songMetadata.getTitle(),
                                                "artist", songMetadata.getArtist(),
                                                "fileName", fileMetadata.getFileOriginalName(),
                                                "downloadDurationMs", downloadDuration,
                                                "downloadDurationSec", downloadDuration / 1000,
                                                "timestamp", downloadEndTime),
                                        traceId);
                                
                                // Publish WARNING if download took longer than 30 seconds
                                if (downloadDuration > 30000) {
                                    metricPublisher.publishMetric(brand, MetricEventType.WARNING, "file_download_slow",
                                            Map.of("songId", songMetadata.getSongId().toString(),
                                                    "title", songMetadata.getTitle(),
                                                    "artist", songMetadata.getArtist(),
                                                    "fileName", fileMetadata.getFileOriginalName(),
                                                    "downloadDurationMs", downloadDuration,
                                                    "downloadDurationSec", downloadDuration / 1000,
                                                    "threshold", "30 seconds"),
                                            traceId);
                                }
                            })
                            .onItem().transformToUni(tempFile -> {
                                //LOGGER.infof("%s Segmenting: %s", logPrefix(), songMetadata.getTitle());
                                
                                // TEMP METRIC - Track segmentation timing
                                long segmentationStartTime = System.currentTimeMillis();
                                metricPublisher.publishMetric(brand, MetricEventType.DEBUG, "segmentation_started",
                                        Map.of("songId", songMetadata.getSongId().toString(),
                                                "title", songMetadata.getTitle(),
                                                "artist", songMetadata.getArtist(),
                                                "timestamp", segmentationStartTime),
                                        traceId);
                                
                                return segmentationService.slice(songMetadata, tempFile, bitRates)
                                        .ifNoItem().after(Duration.ofMinutes(3)).fail()
                                        .onFailure().invoke(e -> {
                                            LOGGER.errorf(e, "%s Segmentation FAILED for %s", logPrefix(), songMetadata.getTitle());
                                            
                                            // TEMP METRIC - Track segmentation failure
                                            long segmentationEndTime = System.currentTimeMillis();
                                            long segmentationDuration = segmentationEndTime - segmentationStartTime;
                                            boolean isTimeout = e instanceof java.util.concurrent.TimeoutException || 
                                                               e.getMessage() != null && e.getMessage().contains("timeout");
                                            
                                            metricPublisher.publishMetric(brand, MetricEventType.ERROR, "segmentation_failed",
                                                    Map.of("songId", songMetadata.getSongId().toString(),
                                                            "title", songMetadata.getTitle(),
                                                            "artist", songMetadata.getArtist(),
                                                            "segmentationDurationMs", segmentationDuration,
                                                            "segmentationDurationSec", segmentationDuration / 1000,
                                                            "isTimeout", isTimeout,
                                                            "errorMessage", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                                            "timestamp", segmentationEndTime),
                                                    traceId);
                                        })
                                        .onItem().invoke(segments -> {
                                            // TEMP METRIC - Track segmentation completion and duration
                                            long segmentationEndTime = System.currentTimeMillis();
                                            long segmentationDuration = segmentationEndTime - segmentationStartTime;
                                            metricPublisher.publishMetric(brand, MetricEventType.DEBUG, "segmentation_completed",
                                                    Map.of("songId", songMetadata.getSongId().toString(),
                                                            "title", songMetadata.getTitle(),
                                                            "artist", songMetadata.getArtist(),
                                                            "segmentationDurationMs", segmentationDuration,
                                                            "segmentationDurationSec", segmentationDuration / 1000,
                                                            "segmentCount", segments.isEmpty() ? 0 : segments.values().iterator().next().size(),
                                                            "timestamp", segmentationEndTime),
                                                    traceId);
                                            
                                            // Publish WARNING if segmentation took longer than 20 seconds
                                            if (segmentationDuration > 20000) {
                                                metricPublisher.publishMetric(brand, MetricEventType.WARNING, "segmentation_slow",
                                                        Map.of("songId", songMetadata.getSongId().toString(),
                                                                "title", songMetadata.getTitle(),
                                                                "artist", songMetadata.getArtist(),
                                                                "segmentationDurationMs", segmentationDuration,
                                                                "segmentationDurationSec", segmentationDuration / 1000,
                                                                "threshold", "20 seconds"),
                                                        traceId);
                                            }
                                        })
                                        .onItem().invoke(segments -> {
                                            try {
                                                Files.deleteIfExists(tempFile);
                                            } catch (Exception e) {
                                                LOGGER.warnf("%s Failed to delete temp file: %s", logPrefix(), tempFile);
                                            }
                                        })
                                        .onItem().transformToUni(segments -> {
                                            if (segments.isEmpty()) {
                                                LOGGER.warnf("%s No segments for fragment: %s", logPrefix(), soundFragment.getId());
                                                return Uni.createFrom().item(false);
                                            }
                                            liveSoundFragment.setSegments(segments);
                                            if (priority > 9) {
                                                playlistState.regularQueue.add(liveSoundFragment);
                                                LOGGER.infof("%s ✓ Added to regular queue: %s - %s (%d segments)",
                                                        logPrefix(), songMetadata.getTitle(), songMetadata.getArtist(),
                                                        segments.values().stream().findFirst().map(ConcurrentLinkedQueue::size).orElse(0));
                                                // TEMP METRIC - Remove after delay investigation
                                                metricPublisher.publishMetric(brand, MetricEventType.DEBUG, "song_added_to_regular_queue",
                                                        Map.of("songId", songMetadata.getSongId().toString(),
                                                                "title", songMetadata.getTitle(),
                                                                "artist", songMetadata.getArtist(),
                                                                "queueSize", playlistState.regularQueue.size(),
                                                                "timestamp", System.currentTimeMillis()),
                                                        traceId);
                                            } else {
                                                playlistState.prioritizedQueue.add(liveSoundFragment);
                                                LOGGER.infof("%s ✓ Added to prioritized queue: %s - %s (%d segments)",
                                                        logPrefix(), songMetadata.getTitle(), songMetadata.getArtist(),
                                                        segments.values().stream().findFirst().map(ConcurrentLinkedQueue::size).orElse(0));
                                                // TEMP METRIC - Remove after delay investigation
                                                metricPublisher.publishMetric(brand, MetricEventType.DEBUG, "song_added_to_prioritized_queue",
                                                        Map.of("songId", songMetadata.getSongId().toString(),
                                                                "title", songMetadata.getTitle(),
                                                                "artist", songMetadata.getArtist(),
                                                                "queueSize", playlistState.prioritizedQueue.size(),
                                                                "timestamp", System.currentTimeMillis()),
                                                        traceId);
                                            }
                                            publishQueueMetricsSafe(traceId);
                                            return Uni.createFrom().item(true);
                                        });
                            })
                            .onFailure().recoverWithItem(e -> {
                                LOGGER.errorf(e, "%s Failed to process file: %s", logPrefix(), fileMetadata.getFileOriginalName());
                                return false;
                            });
                });
    }

    private Uni<Boolean> processTempFile(Path tempPath, LiveSoundFragment liveSoundFragment, SongMetadata songMetadata, int priority) {
        LOGGER.infof("%s Segmenting temporary file: %s", logPrefix(), songMetadata.getTitle());
        
        // TEMP METRIC - Track segmentation timing for pre-mixed files
        long segmentationStartTime = System.currentTimeMillis();
        metricPublisher.publishMetric(brand, MetricEventType.DEBUG, "segmentation_started",
                Map.of("songId", songMetadata.getSongId().toString(),
                        "title", songMetadata.getTitle(),
                        "artist", songMetadata.getArtist(),
                        "source", "pre_mixed_file",
                        "timestamp", segmentationStartTime),
                songMetadata.getTraceId());
        
        return segmentationService.slice(songMetadata, tempPath, bitRates)
                .ifNoItem().after(Duration.ofMinutes(3)).fail()
                .onFailure().invoke(e -> {
                    LOGGER.errorf(e, "%s Segmentation FAILED for %s", logPrefix(), songMetadata.getTitle());
                    
                    // TEMP METRIC - Track segmentation failure
                    long segmentationEndTime = System.currentTimeMillis();
                    long segmentationDuration = segmentationEndTime - segmentationStartTime;
                    boolean isTimeout = e instanceof java.util.concurrent.TimeoutException || 
                                       e.getMessage() != null && e.getMessage().contains("timeout");
                    
                    metricPublisher.publishMetric(brand, MetricEventType.ERROR, "segmentation_failed",
                            Map.of("songId", songMetadata.getSongId().toString(),
                                    "title", songMetadata.getTitle(),
                                    "artist", songMetadata.getArtist(),
                                    "source", "pre_mixed_file",
                                    "segmentationDurationMs", segmentationDuration,
                                    "segmentationDurationSec", segmentationDuration / 1000,
                                    "isTimeout", isTimeout,
                                    "errorMessage", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                    "timestamp", segmentationEndTime),
                            songMetadata.getTraceId());
                })
                .onItem().invoke(segments -> {
                    // TEMP METRIC - Track segmentation completion and duration
                    long segmentationEndTime = System.currentTimeMillis();
                    long segmentationDuration = segmentationEndTime - segmentationStartTime;
                    metricPublisher.publishMetric(brand, MetricEventType.DEBUG, "segmentation_completed",
                            Map.of("songId", songMetadata.getSongId().toString(),
                                    "title", songMetadata.getTitle(),
                                    "artist", songMetadata.getArtist(),
                                    "source", "pre_mixed_file",
                                    "segmentationDurationMs", segmentationDuration,
                                    "segmentationDurationSec", segmentationDuration / 1000,
                                    "segmentCount", segments.isEmpty() ? 0 : segments.values().iterator().next().size(),
                                    "timestamp", segmentationEndTime),
                            songMetadata.getTraceId());
                    
                    // Publish WARNING if segmentation took longer than 20 seconds
                    if (segmentationDuration > 20000) {
                        metricPublisher.publishMetric(brand, MetricEventType.WARNING, "segmentation_slow",
                                Map.of("songId", songMetadata.getSongId().toString(),
                                        "title", songMetadata.getTitle(),
                                        "artist", songMetadata.getArtist(),
                                        "source", "pre_mixed_file",
                                        "segmentationDurationMs", segmentationDuration,
                                        "segmentationDurationSec", segmentationDuration / 1000,
                                        "threshold", "20 seconds"),
                                songMetadata.getTraceId());
                    }
                })
                .onItem().transformToUni(segments -> {
                    if (segments.isEmpty()) {
                        LOGGER.warnf("%s No segments for fragment: %s", logPrefix(), songMetadata.getSongId());
                        return Uni.createFrom().item(false);
                    }
                    liveSoundFragment.setSegments(segments);
                    if (priority > 9) {
                        playlistState.regularQueue.add(liveSoundFragment);
                        LOGGER.infof("%s ✓ Added to regular queue: %s - %s (%d segments)",
                                logPrefix(), songMetadata.getTitle(), songMetadata.getArtist(),
                                segments.values().stream().findFirst().map(ConcurrentLinkedQueue::size).orElse(0));
                        // TEMP METRIC - Remove after delay investigation
                        metricPublisher.publishMetric(brand, MetricEventType.DEBUG, "song_added_to_regular_queue",
                                Map.of("songId", songMetadata.getSongId().toString(),
                                        "title", songMetadata.getTitle(),
                                        "artist", songMetadata.getArtist(),
                                        "queueSize", playlistState.regularQueue.size(),
                                        "timestamp", System.currentTimeMillis()),
                                songMetadata.getTraceId());
                    } else {
                        playlistState.prioritizedQueue.add(liveSoundFragment);
                        LOGGER.infof("%s ✓ Added to prioritized queue: %s - %s (%d segments)",
                                logPrefix(), songMetadata.getTitle(), songMetadata.getArtist(),
                                segments.values().stream().findFirst().map(ConcurrentLinkedQueue::size).orElse(0));
                        // TEMP METRIC - Remove after delay investigation
                        metricPublisher.publishMetric(brand, MetricEventType.DEBUG, "song_added_to_prioritized_queue",
                                Map.of("songId", songMetadata.getSongId().toString(),
                                        "title", songMetadata.getTitle(),
                                        "artist", songMetadata.getArtist(),
                                        "queueSize", playlistState.prioritizedQueue.size(),
                                        "timestamp", System.currentTimeMillis()),
                                songMetadata.getTraceId());
                    }
                    publishQueueMetricsSafe(songMetadata.getTraceId());
                    return Uni.createFrom().item(true);
                });
    }


    public LiveSoundFragment getNextLiveFragment() {
        if (!initialized) {
            LOGGER.infof("%s Not initialized, triggering lazy initialization", logPrefix());
            ensureInitialized().await().indefinitely();
        }

        LOGGER.debugf("%s Queues: prioritized=%d, regular=%d",
                logPrefix(), playlistState.prioritizedQueue.size(), playlistState.regularQueue.size());

        if (!playlistState.prioritizedQueue.isEmpty()) {
            LiveSoundFragment next = playlistState.prioritizedQueue.poll();
            // TEMP METRIC - Remove after delay investigation
            if (next != null && next.getMetadata() != null) {
                metricPublisher.publishMetric(brand, MetricEventType.DEBUG, "fragment_polled_from_queue",
                        Map.of("songId", next.getMetadata().getSongId().toString(),
                                "title", next.getMetadata().getTitle(),
                                "artist", next.getMetadata().getArtist(),
                                "queueType", "prioritized",
                                "remainingInQueue", playlistState.prioritizedQueue.size(),
                                "timestamp", System.currentTimeMillis()),
                        next.getMetadata().getTraceId());
            }
            publishQueueMetricsSafe(next != null && next.getMetadata() != null ? next.getMetadata().getTraceId() : null);
            moveFragmentToProcessedList(next);
            return next;
        }

        if (!playlistState.regularQueue.isEmpty()) {
            LiveSoundFragment next = playlistState.regularQueue.poll();
            // TEMP METRIC - Remove after delay investigation
            if (next != null && next.getMetadata() != null) {
                metricPublisher.publishMetric(brand, MetricEventType.DEBUG, "fragment_polled_from_queue",
                        Map.of("songId", next.getMetadata().getSongId().toString(),
                                "title", next.getMetadata().getTitle(),
                                "artist", next.getMetadata().getArtist(),
                                "queueType", "regular",
                                "remainingInQueue", playlistState.regularQueue.size(),
                                "timestamp", System.currentTimeMillis()),
                        next.getMetadata().getTraceId());
            }
            publishQueueMetricsSafe(next != null && next.getMetadata() != null ? next.getMetadata().getTraceId() : null);
            moveFragmentToProcessedList(next);
            return next;
        }

        LOGGER.warnf("%s Queues empty, triggering starving feed", logPrefix());
        // TEMP METRIC - Remove after delay investigation
        /*metricPublisher.publishMetric(brand, MetricEventType.DEBUG, "waiting_melody_returned",
                Map.of("prioritizedQueueSize", playlistState.prioritizedQueue.size(),
                        "regularQueueSize", playlistState.regularQueue.size(),
                        "timestamp", System.currentTimeMillis()));*/
        // Dispatch onto Vert.x event loop — never block or subscribe from caller thread
     /*   vertx.runOnContext(() -> feedFragments(1, true)
                .subscribe().with(
                        v -> LOGGER.debugf("%s Starving feed complete", logPrefix()),
                        e -> LOGGER.errorf(e, "%s Starving feed failed", logPrefix())
                ));*/

        if (waitingAudioProvider.isWaitingAudioAvailable()) {
            return waitingAudioProvider.createWaitingFragment()
                    .await().atMost(Duration.ofSeconds(5));
        }
        return null;
    }

    private void moveFragmentToProcessedList(LiveSoundFragment fragment) {
        if (fragment == null) return;
        slicedFragmentsLock.writeLock().lock();
        try {
            playlistState.obtainedByHlsPlaylist.add(fragment);
            LOGGER.debugf("%s Queued fragment: %s (processed: %d)",
                    logPrefix(), fragment.getMetadata(), playlistState.obtainedByHlsPlaylist.size());
            if (playlistState.obtainedByHlsPlaylist.size() > PROCESSED_QUEUE_MAX_SIZE) {
                LiveSoundFragment removed = playlistState.obtainedByHlsPlaylist.poll();
                LOGGER.tracef("%s Trimmed processed queue, removed: %s", logPrefix(), removed.getMetadata());
            }
        } finally {
            slicedFragmentsLock.writeLock().unlock();
        }
    }

    public void shutdown() {
        LOGGER.infof("%s Shutting down PlaylistManager", logPrefix());
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        LOGGER.errorf("%s Scheduler did not terminate gracefully", logPrefix());
                    }
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
                LOGGER.warnf("%s Shutdown interrupted", logPrefix());
            }
        }
        slicedFragmentsLock.writeLock().lock();
        try {
            playlistState.obtainedByHlsPlaylist.clear();
        } finally {
            slicedFragmentsLock.writeLock().unlock();
        }
        playlistState.regularQueue.clear();
        playlistState.prioritizedQueue.clear();
        publishQueueMetricsSafe(null);
        LOGGER.infof("%s Shutdown complete.", logPrefix());
    }

    private void publishQueueMetricsSafe(UUID traceId) {
        try {
            publishQueueMetrics(traceId);
        } catch (Exception e) {
            LOGGER.errorf(e, "%s Error publishing queue metrics", logPrefix());
        }
    }

    private void publishQueueMetrics(UUID traceId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("brandId", brandId.toString());
        payload.put("regularQueueSongs", getUniqueSongMetadata(playlistState.regularQueue));
        payload.put("prioritizedQueueSongs", getUniqueSongMetadata(playlistState.prioritizedQueue));
        
        MetricEventDTO event = MetricEventDTO.of(
                serviceId,
                brand,
                MetricEventType.INFORMATION,
                traceId != null ? traceId : UUID.randomUUID(),
                "queue_updated",
                payload
        );
        
        metricPublisher.publish(event)
                .subscribe().with(
                        v -> LOGGER.debugf("%s Queue metrics published", logPrefix()),
                        e -> LOGGER.errorf(e, "%s Failed to publish queue metrics", logPrefix())
                );
    }

    private List<Map<String, Object>> getUniqueSongMetadata(java.util.Collection<LiveSoundFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return new ArrayList<>();
        }

        java.util.Set<String> uniqueSongs = new java.util.HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (LiveSoundFragment fragment : fragments) {
            if (fragment != null && fragment.getMetadata() != null) {
                SongMetadata metadata = fragment.getMetadata();
                String songKey = metadata.getTitle() + "|" + metadata.getArtist();
                
                // Only add if we haven't seen this song before
                if (uniqueSongs.add(songKey)) {
                    Map<String, Object> songInfo = new HashMap<>();
                    songInfo.put("songId", metadata.getSongId() != null ? metadata.getSongId().toString() : null);
                    songInfo.put("title", metadata.getTitle());
                    songInfo.put("artist", metadata.getArtist());
                    songInfo.put("album", metadata.getAlbum());
                    songInfo.put("genre", metadata.getGenre());
                    songInfo.put("duration", metadata.getDuration());
                    songInfo.put("languageCode", metadata.getLanguageCode());
                    songInfo.put("itemType", metadata.getItemType() != null ? metadata.getItemType().name() : null);
                    result.add(songInfo);
                }
            }
        }
        
        return result;
    }

    private String logPrefix() {
        return "[" + brand + "]";
    }
}