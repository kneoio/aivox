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
import jakarta.enterprise.context.Dependent;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Dependent
public class PlaylistManager {
    private static final Logger LOGGER = Logger.getLogger(PlaylistManager.class);
    private static final int SELF_MANAGING_INTERVAL_SECONDS = 100;
    private static final int REGULAR_BUFFER_MAX = 2;
    private static final int TRIGGER_SELF_MANAGING = 2;
    private static final int PROCESSED_QUEUE_MAX_SIZE = 2;
    private static final long STARVING_FEED_COOLDOWN_MILLIS = 20_000L;
    private final ReadWriteLock slicedFragmentsLock = new ReentrantReadWriteLock();
    private final Map<String, PlaylistState> playlistStates = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private volatile long lastStarvingFeedTime = 0;
    
    private final AivoxConfig aivoxConfig;
    private final WaitingAudioProvider waitingAudioProvider;
    private final SoundFragmentBrandService soundFragmentBrandService;
    private final BrandService brandService;
    private final SoundFragmentFileHandler fileHandler;
    private final AudioSegmentationService segmentationService;
    private final Map<String, UUID> brandIdCache = new ConcurrentHashMap<>();

    public PlaylistManager(AivoxConfig aivoxConfig, WaitingAudioProvider waitingAudioProvider,
                           SoundFragmentBrandService soundFragmentBrandService,
                           BrandService brandService, SoundFragmentFileHandler fileHandler,
                           AudioSegmentationService segmentationService) {
        this.aivoxConfig = aivoxConfig;
        this.waitingAudioProvider = waitingAudioProvider;
        this.soundFragmentBrandService = soundFragmentBrandService;
        this.brandService = brandService;
        this.fileHandler = fileHandler;
        this.segmentationService = segmentationService;

        waitingAudioProvider.initialize();
        // Start self-managing scheduler
        scheduler.scheduleAtFixedRate(() -> {
            try {
                for (Map.Entry<String, PlaylistState> entry : playlistStates.entrySet()) {
                    String brand = entry.getKey();
                    PlaylistState state = entry.getValue();
                    
                    if (state.regularQueue.size() <= TRIGGER_SELF_MANAGING) {
                        if (Math.random() < 0.5) {
                            feedFragments(brand, 1, false);
                        } else {
                            feedFragments(brand, 2, false);
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error during maintenance: " + e.getMessage(), e);
            }
        }, 30, SELF_MANAGING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void feedFragments(String brand, int maxQuantity, boolean useCooldown) {
        PlaylistState state = playlistStates.get(brand);
        if (state == null) return;

        if (useCooldown) {
            long now = System.currentTimeMillis();
            if (now - lastStarvingFeedTime < STARVING_FEED_COOLDOWN_MILLIS) {
                LOGGER.debug("Station starving but cooldown active, waiting " + 
                    (STARVING_FEED_COOLDOWN_MILLIS - (now - lastStarvingFeedTime)) + " ms");
                return;
            }
            lastStarvingFeedTime = now;
        }

        int remaining = Math.max(0, REGULAR_BUFFER_MAX - state.regularQueue.size());
        if (remaining == 0) {
            LOGGER.debug("Skipping addFragments - regular buffer at cap " + REGULAR_BUFFER_MAX + " for brand " + brand);
            return;
        }

        int quantityToFetch = Math.min(remaining, maxQuantity);
        LOGGER.info("Feeding " + quantityToFetch + " fragments for brand " + brand);

        // Get excluded IDs from current queues
        List<UUID> excludedIds = new ArrayList<>();
        state.regularQueue.forEach(f -> excludedIds.add(f.getSoundFragmentId()));
        state.prioritizedQueue.forEach(f -> excludedIds.add(f.getSoundFragmentId()));
        slicedFragmentsLock.readLock().lock();
        try {
            state.obtainedByHlsPlaylist.forEach(f -> excludedIds.add(f.getSoundFragmentId()));
        } finally {
            slicedFragmentsLock.readLock().unlock();
        }

        // Fetch songs from database like legacy
        UUID brandId = resolveBrandId(brand);
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
                        return addFragmentToQueue(brand, fragment);
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

    private UUID resolveBrandId(String brandSlug) {
        UUID cachedId = brandIdCache.get(brandSlug);
        if (cachedId != null) {
            return cachedId;
        }
        try {
            UUID brandId = brandService.getBySlugName(brandSlug)
                    .await().atMost(Duration.ofSeconds(5))
                    .getId();
            brandIdCache.put(brandSlug, brandId);
            return brandId;
        } catch (Exception e) {
            LOGGER.error("Failed to resolve brand ID for: " + brandSlug + ", error: " + e.getMessage());
            return null;
        }
    }

    private Uni<Boolean> addFragmentToQueue(String brand, SoundFragment soundFragment) {
        PlaylistState state = playlistStates.get(brand);
        if (state == null) {
            return Uni.createFrom().item(false);
        }

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
                                state.regularQueue.add(liveSoundFragment);
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


    public LiveSoundFragment getNextLiveFragment(String brand) {
        LOGGER.info("getNextLiveFragment called for brand: " + brand);
        PlaylistState state = playlistStates.get(brand);
        if (state == null) {
            LOGGER.warn("No playlist state found for brand: " + brand + ", creating new state");
            state = new PlaylistState();
            playlistStates.put(brand, state);
        }

        LiveSoundFragment fragment = getNextFragment(brand);
        LOGGER.info("getNextFragment returned: " + (fragment != null ? "fragment with ID " + fragment.getSoundFragmentId() : "NULL"));
        return fragment;
    }

    private LiveSoundFragment getNextFragment(String brand) {
        PlaylistState state = playlistStates.get(brand);
        if (state == null) {
            LOGGER.warn("getNextFragment: No state for brand: " + brand);
            return null;
        }

        LOGGER.info("getNextFragment for brand: " + brand + ", prioritizedQueue: " + state.prioritizedQueue.size() + ", regularQueue: " + state.regularQueue.size());

        // Check prioritized queue first
        if (!state.prioritizedQueue.isEmpty()) {
            LOGGER.info("Returning fragment from prioritizedQueue");
            LiveSoundFragment nextFragment = state.prioritizedQueue.poll();
            moveFragmentToProcessedList(brand, nextFragment);
            return nextFragment;
        }

        // Then check regular queue
        if (!state.regularQueue.isEmpty()) {
            LOGGER.info("Returning fragment from regularQueue");
            LiveSoundFragment nextFragment = state.regularQueue.poll();
            moveFragmentToProcessedList(brand, nextFragment);
            return nextFragment;
        }
        
        LOGGER.warn("Both queues are empty for brand: " + brand + ", activating waiting state");

        // Trigger feeding for next cycle
        feedFragments(brand, 1, true);
        
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

    private void moveFragmentToProcessedList(String brand, LiveSoundFragment fragmentToPlay) {
        if (fragmentToPlay != null) {
            PlaylistState state = playlistStates.get(brand);
            if (state != null) {
                slicedFragmentsLock.writeLock().lock();
                try {
                    state.obtainedByHlsPlaylist.add(fragmentToPlay);
                    
                    // Add to MP3 fragments list
                    state.fragmentsForMp3.add(fragmentToPlay);
                    while (state.fragmentsForMp3.size() > 2) {
                        state.fragmentsForMp3.removeFirst();
                    }

                    LOGGER.info("Queued fragment for brand=" + brand + " id=" + fragmentToPlay.getSoundFragmentId().toString());
                    if (state.obtainedByHlsPlaylist.size() > PROCESSED_QUEUE_MAX_SIZE) {
                        LiveSoundFragment removed = state.obtainedByHlsPlaylist.poll();
                        LOGGER.debug("Removed oldest fragment from processed queue: " + removed.getMetadata());
                    }
                } finally {
                    slicedFragmentsLock.writeLock().unlock();
                }
            }
        }
    }

    public Uni<Boolean> addFragment(String brand, LiveSoundFragment fragment, int priority) {
        PlaylistState state = playlistStates.computeIfAbsent(brand, k -> new PlaylistState());
        
        try {
            fragment.setPriority(priority);
            
            if (priority <= 12) {
                // High priority - add to prioritized queue
                state.prioritizedQueue.offer(fragment);
                LOGGER.info("Added prioritized fragment for brand " + brand);
            } else {
                // Regular priority - add to regular queue
                if (state.regularQueue.size() >= REGULAR_BUFFER_MAX) {
                    LOGGER.debug("Refusing to add regular fragment; buffer full (" + REGULAR_BUFFER_MAX + "). Brand: " + brand);
                    return Uni.createFrom().item(false);
                }
                state.regularQueue.offer(fragment);
                LOGGER.info("Added regular fragment for brand " + brand);
            }
            
            return Uni.createFrom().item(true);
        } catch (Exception e) {
            LOGGER.error("Error adding fragment for brand " + brand + ": " + e.getMessage(), e);
            return Uni.createFrom().item(false);
        }
    }

    public Uni<Void> initializeBrand(String brand) {
        PlaylistState state = playlistStates.computeIfAbsent(brand, k -> {
            LOGGER.info("Initializing playlist for brand: " + brand);
            return new PlaylistState();
        });
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
                            state.regularQueue.offer(fragment);
                        }
                    });
                    LOGGER.info("Brand " + brand + " initialized with " + state.regularQueue.size() + " waiting audio fragments. Station ready to stream.");
                })
                .replaceWithVoid();
    }

    public void shutdownBrand(String brand) {
        PlaylistState state = playlistStates.remove(brand);
        if (state != null) {
            slicedFragmentsLock.writeLock().lock();
            try {
                state.obtainedByHlsPlaylist.clear();
                state.fragmentsForMp3.clear();
            } finally {
                slicedFragmentsLock.writeLock().unlock();
            }
            
            state.regularQueue.clear();
            state.prioritizedQueue.clear();
            LOGGER.info("PlaylistManager for " + brand + " has been shut down. All queues cleared.");
        }
    }

    public void shutdown() {
        LOGGER.info("Shutting down PlaylistManager");
        
        scheduler.shutdownNow();
        
        slicedFragmentsLock.writeLock().lock();
        try {
            for (PlaylistState state : playlistStates.values()) {
                state.obtainedByHlsPlaylist.clear();
                state.fragmentsForMp3.clear();
            }
        } finally {
            slicedFragmentsLock.writeLock().unlock();
        }
        
        for (PlaylistState state : playlistStates.values()) {
            state.regularQueue.clear();
            state.prioritizedQueue.clear();
        }
        
        playlistStates.clear();
        LOGGER.info("PlaylistManager has been shut down. All queues cleared.");
    }

    // Inner class to maintain brand-specific state
    private static class PlaylistState {
        final LinkedList<LiveSoundFragment> obtainedByHlsPlaylist = new LinkedList<>();
        final PriorityQueue<LiveSoundFragment> regularQueue = new PriorityQueue<>(
            Comparator.comparing(LiveSoundFragment::getQueueNum)
        );
        final PriorityQueue<LiveSoundFragment> prioritizedQueue = new PriorityQueue<>(
            Comparator.comparing(LiveSoundFragment::getPriority)
                .thenComparing(LiveSoundFragment::getQueueNum)
        );
        final LinkedList<LiveSoundFragment> fragmentsForMp3 = new LinkedList<>();
    }
}
