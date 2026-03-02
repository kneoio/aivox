package com.semantyca.aivox.service.playlist;

import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.model.soundfragment.SoundFragment;
import com.semantyca.aivox.repository.soundfragment.SoundFragmentFileHandler;
import com.semantyca.aivox.service.BrandService;
import com.semantyca.aivox.service.SoundFragmentBrandService;
import com.semantyca.aivox.service.manipulation.AudioSegmentationService;
import com.semantyca.aivox.streaming.HlsSegment;
import com.semantyca.aivox.streaming.LiveSoundFragment;
import com.semantyca.aivox.streaming.SongMetadata;
import com.semantyca.aivox.streaming.WaitingAudioProvider;
import com.semantyca.core.model.FileMetadata;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
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

public class PlaylistManager {
    private static final Logger LOGGER = Logger.getLogger(PlaylistManager.class);
    private static final int SELF_MANAGING_INTERVAL_SECONDS = 100;
    private static final int REGULAR_BUFFER_MAX = 2;
    private static final int TRIGGER_SELF_MANAGING = 2;
    private static final int PROCESSED_QUEUE_MAX_SIZE = 2;
    private static final long STARVING_FEED_COOLDOWN_MILLIS = 20_000L;
    private static final int HIGH_PRIORITY_THRESHOLD = 12;
    private final ReadWriteLock slicedFragmentsLock = new ReentrantReadWriteLock();
    private final PlaylistState playlistState = new PlaylistState();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private volatile long lastStarvingFeedTime = 0;

    private final String brand;
    private final AivoxConfig aivoxConfig;
    private final WaitingAudioProvider waitingAudioProvider;
    private final SoundFragmentBrandService soundFragmentBrandService;
    private final BrandService brandService;
    private final SoundFragmentFileHandler fileHandler;
    private final AudioSegmentationService segmentationService;
    private UUID brandId;

    public PlaylistManager(String brand, AivoxConfig aivoxConfig, WaitingAudioProvider waitingAudioProvider,
                           SoundFragmentBrandService soundFragmentBrandService,
                           BrandService brandService, SoundFragmentFileHandler fileHandler,
                           AudioSegmentationService segmentationService) {
        this.brand = brand;
        this.aivoxConfig = aivoxConfig;
        this.waitingAudioProvider = waitingAudioProvider;
        this.soundFragmentBrandService = soundFragmentBrandService;
        this.brandService = brandService;
        this.fileHandler = fileHandler;
        this.segmentationService = segmentationService;

        // Start self-managing scheduler
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (playlistState.regularQueue.size() <= TRIGGER_SELF_MANAGING) {
                    if (Math.random() < 0.5) {
                        feedFragments(1, false);
                    } else {
                        feedFragments(2, false);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error during maintenance: " + e.getMessage(), e);
            }
        }, 30, SELF_MANAGING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void feedFragments(int maxQuantity, boolean useCooldown) {
        if (useCooldown) {
            long now = System.currentTimeMillis();
            if (now - lastStarvingFeedTime < STARVING_FEED_COOLDOWN_MILLIS) {
                LOGGER.debug("Station starving but cooldown active, waiting " +
                        (STARVING_FEED_COOLDOWN_MILLIS - (now - lastStarvingFeedTime)) + " ms");
                return;
            }
            lastStarvingFeedTime = now;
        }

        int remaining = Math.max(0, REGULAR_BUFFER_MAX - playlistState.regularQueue.size());
        if (remaining == 0) {
            LOGGER.debug("Skipping addFragments - regular buffer at cap " + REGULAR_BUFFER_MAX + " for brand " + brand);
            return;
        }

        int quantityToFetch = Math.min(remaining, maxQuantity);
        LOGGER.info("Feeding " + quantityToFetch + " fragments for brand " + brand);

        // Get excluded IDs from current queues - thread-safe collection
        List<UUID> excludedIds = new ArrayList<>();
        excludedIds.addAll(playlistState.regularQueue.stream()
                .map(LiveSoundFragment::getSoundFragmentId)
                .toList());
        excludedIds.addAll(playlistState.prioritizedQueue.stream()
                .map(LiveSoundFragment::getSoundFragmentId)
                .toList());
        slicedFragmentsLock.readLock().lock();
        try {
            excludedIds.addAll(playlistState.obtainedByHlsPlaylist.stream()
                    .map(LiveSoundFragment::getSoundFragmentId)
                    .toList());
        } finally {
            slicedFragmentsLock.readLock().unlock();
        }

        // Fetch songs from database
        if (brandId == null) {
            LOGGER.warn("Cannot resolve brand ID for: " + brand);
            return;
        }

        soundFragmentBrandService.getBrandSongs(brandId, PlaylistItemType.SONG)
                .onItem().transformToMulti(soundFragments -> Multi.createFrom().iterable(soundFragments))
                .onItem().transform(fragment -> {
                    // Filter out excluded IDs
                    if (excludedIds.contains(fragment.getId())) {
                        return null;
                    }
                    return fragment;
                })
                .filter(Objects::nonNull)
                .select().first(quantityToFetch)
                .onItem().call(fragment -> {
                    try {
                        assert fragment != null;
                        return addFragmentToQueue(fragment);
                    } catch (Exception e) {
                        LOGGER.warn("Skipping fragment due to error: " + e.getMessage());
                        return Uni.createFrom().item(false);
                    }
                })
                .collect().asList()
                .subscribe().with(
                        processedItems -> {
                            LOGGER.info("Successfully processed and added " + processedItems.size() + " fragments for brand " + brand);
                        },
                        error -> {
                            LOGGER.error("Error during the processing of fragments for brand " + brand + ": " + error.getMessage(), error);
                        }
                );
    }

    private Uni<Void> resolveBrandId() {
        if (brandId != null) {
            return Uni.createFrom().voidItem();
        }
        return brandService.getBySlugName(brand)
                .onItem().invoke(brandEntity -> {
                    brandId = brandEntity.getId();
                    LOGGER.info("Resolved brand ID for: " + brand);
                })
                .onFailure().invoke(e -> 
                    LOGGER.error("Failed to resolve brand ID for: " + brand + ", error: " + e.getMessage())
                )
                .replaceWithVoid();
    }

    private Uni<Boolean> addFragmentToQueue(SoundFragment soundFragment) {
        LiveSoundFragment liveSoundFragment = new LiveSoundFragment();
        SongMetadata songMetadata = new SongMetadata(soundFragment.getId(), soundFragment.getTitle(), soundFragment.getArtist());
        liveSoundFragment.setSoundFragmentId(soundFragment.getId());
        liveSoundFragment.setMetadata(songMetadata);

        // Materialize file from storage and create HLS segments using FFmpeg
        return fileHandler.getFirstFile(soundFragment.getId())
                .onFailure().recoverWithUni(ex -> {
                    LOGGER.warn("Failed to retrieve file for fragment " + soundFragment.getId() + ": " + ex.getMessage());
                    return Uni.createFrom().item((FileMetadata) null);
                })
                .onItem().transformToUni(fileMetadata -> {
                    if (fileMetadata == null) {
                        LOGGER.warn("No file found for fragment: " + soundFragment.getId());
                        return Uni.createFrom().item(false);
                    }

                    return materializeAndSegment(fileMetadata, songMetadata)
                            .onItem().transformToUni(segments -> {
                                if (segments.isEmpty()) {
                                    LOGGER.warn("No segments created for fragment: " + soundFragment.getId());
                                    return Uni.createFrom().item(false);
                                }

                                liveSoundFragment.setSegments(segments);
                                playlistState.regularQueue.add(liveSoundFragment);
                                LOGGER.info("Added fragment from database for brand " + brand + ": " + soundFragment.getTitle());
                                return Uni.createFrom().item(true);
                            });
                });
    }

    private Uni<Map<Long, ConcurrentLinkedQueue<HlsSegment>>> materializeAndSegment(FileMetadata metadata, SongMetadata songMetadata) {
        // Materialize file to temp directory
        InputStream inputStream = metadata.getInputStream();
        if (inputStream == null) {
            LOGGER.warn("No input stream in file metadata: " + metadata.getFileOriginalName());
            return Uni.createFrom().item(new HashMap<>());
        }

        return Uni.createFrom().item(() -> {
                    try {
                        // Create temp file
                        String extension = getFileExtension(metadata.getMimeType());
                        Path tempDir = java.nio.file.Paths.get(aivoxConfig.path().temp());
                        Files.createDirectories(tempDir);
                        Path tempFile = Files.createTempFile(tempDir, "audio_", extension);

                        // Write stream to temp file
                        Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                        inputStream.close();

                        LOGGER.debug("Materialized audio file to: " + tempFile);
                        return tempFile;
                    } catch (Exception e) {
                        LOGGER.error("Failed to materialize file: " + metadata.getFileOriginalName(), e);
                        throw new RuntimeException(e);
                    }
                })
                .chain(tempFile -> {
                    // Use FFmpeg to create proper HLS segments
                    long[] bitrates = {128000L, 64000L};
                    return segmentationService.slice(songMetadata, tempFile, List.of(bitrates[0], bitrates[1]))
                            .onItem().invoke(() -> {
                                // Clean up temp file after segmentation
                                try {
                                    Files.deleteIfExists(tempFile);
                                } catch (Exception e) {
                                    LOGGER.warn("Failed to delete temp file: " + tempFile, e);
                                }
                            });
                });
    }

    private String getFileExtension(String mimeType) {
        if (mimeType == null) {
            return ".tmp";
        }
        return switch (mimeType.toLowerCase()) {
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/wav", "audio/wave", "audio/vnd.wave" -> ".wav";
            case "audio/flac", "audio/x-flac" -> ".flac";
            case "audio/aac" -> ".aac";
            case "audio/ogg" -> ".ogg";
            case "audio/m4a" -> ".m4a";
            default -> ".tmp";
        };
    }

    public LiveSoundFragment getNextLiveFragment() {
        LOGGER.info("getNextLiveFragment called for brand: " + brand);
        LiveSoundFragment fragment = getNextFragment();
        LOGGER.info("getNextFragment returned: " + (fragment != null ? "fragment with ID " + fragment.getSoundFragmentId() : "NULL"));
        return fragment;
    }

    private LiveSoundFragment getNextFragment() {
        LOGGER.info("getNextFragment for brand: " + brand + ", prioritizedQueue: " + playlistState.prioritizedQueue.size() + ", regularQueue: " + playlistState.regularQueue.size());

        // Check prioritized queue first
        if (!playlistState.prioritizedQueue.isEmpty()) {
            LOGGER.info("Returning fragment from prioritizedQueue");
            LiveSoundFragment nextFragment = playlistState.prioritizedQueue.poll();
            moveFragmentToProcessedList(nextFragment);
            return nextFragment;
        }

        // Then check regular queue
        if (!playlistState.regularQueue.isEmpty()) {
            LOGGER.info("Returning fragment from regularQueue");
            LiveSoundFragment nextFragment = playlistState.regularQueue.poll();
            moveFragmentToProcessedList(nextFragment);
            return nextFragment;
        }

        LOGGER.warn("Both queues are empty for brand: " + brand + ", activating waiting state");

        // Trigger feeding for next cycle
        feedFragments(1, true);

        // Return waiting audio loop
        if (waitingAudioProvider.isWaitingAudioAvailable()) {
            LOGGER.debug("Returning waiting audio loop for brand: " + brand);
            return waitingAudioProvider.createWaitingFragment()
                    .await().atMost(java.time.Duration.ofSeconds(5));
        } else {
            LOGGER.error("Waiting audio not available for brand: " + brand);
            return null;
        }
    }

    private void moveFragmentToProcessedList(LiveSoundFragment fragmentToPlay) {
        if (fragmentToPlay != null) {
            slicedFragmentsLock.writeLock().lock();
            try {
                playlistState.obtainedByHlsPlaylist.add(fragmentToPlay);

                // Add to MP3 fragments list
                playlistState.fragmentsForMp3.add(fragmentToPlay);
                while (playlistState.fragmentsForMp3.size() > 2) {
                    playlistState.fragmentsForMp3.removeFirst();
                }

                LOGGER.info("Queued fragment for brand=" + brand + " id=" + fragmentToPlay.getSoundFragmentId().toString());
                if (playlistState.obtainedByHlsPlaylist.size() > PROCESSED_QUEUE_MAX_SIZE) {
                    LiveSoundFragment removed = playlistState.obtainedByHlsPlaylist.poll();
                    LOGGER.debug("Removed oldest fragment from processed queue: " + removed.getMetadata());
                }
            } finally {
                slicedFragmentsLock.writeLock().unlock();
            }
        }
    }

    public Uni<Boolean> addFragment(LiveSoundFragment fragment, int priority) {
        try {
            fragment.setPriority(priority);

            if (priority <= HIGH_PRIORITY_THRESHOLD) {
                // High priority - add to prioritized queue
                playlistState.prioritizedQueue.offer(fragment);
                LOGGER.info("Added prioritized fragment for brand " + brand);
            } else {
                // Regular priority - add to regular queue
                if (playlistState.regularQueue.size() >= REGULAR_BUFFER_MAX) {
                    LOGGER.debug("Refusing to add regular fragment; buffer full (" + REGULAR_BUFFER_MAX + "). Brand: " + brand);
                    return Uni.createFrom().item(false);
                }
                playlistState.regularQueue.offer(fragment);
                LOGGER.info("Added regular fragment for brand " + brand);
            }

            return Uni.createFrom().item(true);
        } catch (Exception e) {
            LOGGER.error("Error adding fragment for brand " + brand + ": " + e.getMessage(), e);
            return Uni.createFrom().item(false);
        }
    }

    public Uni<Void> initialize() {
        LOGGER.info("Initializing playlist for brand: " + brand);
        
        return resolveBrandId()
                .onItem().transformToUni(v -> {
                    // Initialize waiting audio provider
                    waitingAudioProvider.initialize();
                    
                    List<Uni<LiveSoundFragment>> unis = new ArrayList<>();
                    if (waitingAudioProvider.isWaitingAudioAvailable()) {
                        unis.add(waitingAudioProvider.createWaitingFragment());
                    }

                    if (unis.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }

                    return Uni.join().all(unis).andFailFast()
                            .onItem().invoke(fragments -> {
                                fragments.forEach(fragment -> {
                                    if (fragment != null) {
                                        playlistState.regularQueue.offer(fragment);
                                    }
                                });
                                LOGGER.info("Brand " + brand + " initialized with " + playlistState.regularQueue.size() + " waiting audio fragments. Station ready to stream.");
                            })
                            .replaceWithVoid();
                });
    }

    public void shutdown() {
        LOGGER.info("Shutting down PlaylistManager for brand: " + brand);

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.error("Scheduler did not terminate for brand: " + brand);
                }
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        slicedFragmentsLock.writeLock().lock();
        try {
            playlistState.obtainedByHlsPlaylist.clear();
            playlistState.fragmentsForMp3.clear();
        } finally {
            slicedFragmentsLock.writeLock().unlock();
        }

        playlistState.regularQueue.clear();
        playlistState.prioritizedQueue.clear();

        LOGGER.info("PlaylistManager for " + brand + " has been shut down. All queues cleared.");
    }

    // Inner class to maintain brand-specific state
    private static class PlaylistState {
        final LinkedList<LiveSoundFragment> obtainedByHlsPlaylist = new LinkedList<>();
        final ConcurrentLinkedQueue<LiveSoundFragment> regularQueue = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<LiveSoundFragment> prioritizedQueue = new ConcurrentLinkedQueue<>();
        final LinkedList<LiveSoundFragment> fragmentsForMp3 = new LinkedList<>();
    }
}
