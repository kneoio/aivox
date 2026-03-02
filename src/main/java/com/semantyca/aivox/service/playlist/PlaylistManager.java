package com.semantyca.aivox.service.playlist;

import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.model.soundfragment.SoundFragment;
import com.semantyca.aivox.repository.soundfragment.SoundFragmentFileHandler;
import com.semantyca.aivox.service.BrandService;
import com.semantyca.aivox.service.SoundFragmentBrandService;
import com.semantyca.aivox.service.manipulation.AudioSegmentationService;
import com.semantyca.aivox.streaming.LiveSoundFragment;
import com.semantyca.aivox.streaming.SongMetadata;
import com.semantyca.aivox.streaming.WaitingAudioProvider;
import com.semantyca.core.model.FileMetadata;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    private final WaitingAudioProvider waitingAudioProvider;
    private final SoundFragmentBrandService soundFragmentBrandService;
    private final BrandService brandService;
    private final SoundFragmentFileHandler fileHandler;
    private final AudioSegmentationService segmentationService;
    private final Path tempDir;
    private UUID brandId;


    public PlaylistManager(String brand, AivoxConfig aivoxConfig,
                           WaitingAudioProvider waitingAudioProvider,
                           SoundFragmentBrandService soundFragmentBrandService,
                           BrandService brandService,
                           SoundFragmentFileHandler fileHandler,
                           AudioSegmentationService segmentationService) {
        this.brand = brand;
        this.waitingAudioProvider = waitingAudioProvider;
        this.soundFragmentBrandService = soundFragmentBrandService;
        this.brandService = brandService;
        this.fileHandler = fileHandler;
        this.segmentationService = segmentationService;

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
        LOGGER.infof("%s ========== INITIALIZING PLAYLIST MANAGER ==========", logPrefix());

        return resolveBrandId()
                .onItem().invoke(() -> {
                    LOGGER.infof("%s Brand ID resolved successfully, starting scheduler", logPrefix());
                    startScheduler();
                })
                .onItem().transformToUni(v -> {
                    LOGGER.infof("%s Initializing waiting audio provider", logPrefix());
                    waitingAudioProvider.initialize();

                    List<Uni<LiveSoundFragment>> unis = new ArrayList<>();
                    if (waitingAudioProvider.isWaitingAudioAvailable()) {
                        LOGGER.infof("%s Waiting audio is available, creating fragment", logPrefix());
                        unis.add(waitingAudioProvider.createWaitingFragment());
                    } else {
                        LOGGER.warnf("%s Waiting audio is NOT available", logPrefix());
                    }

                    if (unis.isEmpty()) {
                        LOGGER.warnf("%s No waiting fragments available, but marking as initialized", logPrefix());
                        initialized = true;
                        initializing = false;
                        LOGGER.infof("%s ========== INITIALIZATION COMPLETE: 0 waiting fragments ==========", logPrefix());
                        return Uni.createFrom().voidItem();
                    }

                    return Uni.join().all(unis).andFailFast()
                            .onItem().invoke(fragments -> {
                                fragments.stream()
                                        .filter(Objects::nonNull)
                                        .forEach(playlistState.regularQueue::offer);

                                initialized = true;
                                initializing = false;
                                LOGGER.infof("%s ========== INITIALIZATION COMPLETE: %d waiting fragment(s) ready ==========",
                                        logPrefix(), playlistState.regularQueue.size());
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
                if (playlistState.regularQueue.size() <= TRIGGER_SELF_MANAGING) {
                    int count = Math.random() < 0.5 ? 1 : 2;
                    LOGGER.infof("%s Self-managing: feeding %d frag(s)", logPrefix(), count);
                    feedFragments(count, false);
                }
            } catch (Exception e) {
                LOGGER.errorf(e, "%s Error during maintenance", logPrefix());
            }
        }, 10, SELF_MANAGING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void feedFragments(int maxQuantity, boolean useCooldown) {
        if (useCooldown) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastStarvingFeedTime;
            if (elapsed < STARVING_FEED_COOLDOWN_MILLIS) {
                LOGGER.debugf("%s Cooldown active, waiting %d ms",
                        logPrefix(), STARVING_FEED_COOLDOWN_MILLIS - elapsed);
                return;
            }
            lastStarvingFeedTime = now;
        }

        int remaining = Math.max(0, REGULAR_BUFFER_MAX - playlistState.regularQueue.size());
        if (remaining == 0) {
            LOGGER.debugf("%s Regular buffer at cap (%d), skipping feed",
                    logPrefix(), REGULAR_BUFFER_MAX);
            return;
        }

        int quantityToFetch = Math.min(remaining, maxQuantity);
        LOGGER.infof("%s Feeding %d fragment(s)", logPrefix(), quantityToFetch);

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

        if (brandId == null) {
            LOGGER.warnf("%s Cannot resolve brand ID for slug: %s", logPrefix(), brand);
            return;
        }

        LOGGER.infof("%s Calling getBrandSongs for brandId: %s", logPrefix(), brandId);
        
        soundFragmentBrandService.getBrandSongs(brandId, PlaylistItemType.SONG)
                .ifNoItem().after(Duration.ofSeconds(60)).fail()
                .onFailure().invoke(e -> {
                    LOGGER.errorf("%s Database query timeout - getBrandSongs() took >60s. Check database performance!", logPrefix());
                })
                .onFailure().recoverWithItem(java.util.Collections.emptyList())
                .onItem().invoke(songs -> {
                    if (songs == null) {
                        LOGGER.warnf("%s Retrieved NULL songs list from database", logPrefix());
                    } else {
                        LOGGER.infof("%s Retrieved %d songs from database", logPrefix(), songs.size());
                    }
                })
                .onItem().transform(songs -> {
                    List<SoundFragment> available = songs.stream()
                            .filter(f -> !excludedIds.contains(f.getId()))
                            .collect(java.util.stream.Collectors.toList());
                    LOGGER.infof("%s After filtering: %d available songs", logPrefix(), available.size());
                    java.util.Collections.shuffle(available);
                    return available;
                })
                .onItem().transformToMulti(Multi.createFrom()::iterable)
                .select().first(quantityToFetch)
                .onItem().call(fragment -> {
                    LOGGER.infof("%s Processing song: %s - %s", logPrefix(), fragment.getTitle(), fragment.getArtist());
                    return Uni.createFrom().voidItem();
                })
                .onItem().transformToUniAndMerge(fragment -> {
                    try {
                        assert fragment != null;
                        return addFragmentToQueue(fragment);
                    } catch (Exception e) {
                        LOGGER.warnf("%s Skipping fragment %s: %s",
                                logPrefix(), fragment.getId(), e.getMessage());
                        return Uni.createFrom().item(false);
                    }
                })
                .collect().asList()
                .subscribe().with(
                        processed -> {
                            long successCount = processed.stream()
                                    .filter(b -> b != null && b)
                                    .count();
                            LOGGER.infof("%s Completed: %d/%d fragments added successfully",
                                    logPrefix(), successCount, processed.size());
                        },
                        error -> LOGGER.errorf(error, "%s Processing failed", logPrefix())
                );
    }

    private Uni<Void> resolveBrandId() {
        if (brandId != null) {
            LOGGER.debugf("%s Brand ID already resolved: %s", logPrefix(), brandId);
            return Uni.createFrom().voidItem();
        }
        
        LOGGER.infof("%s Resolving brand ID for slug: %s", logPrefix(), brand);
        return brandService.getBySlugName(brand)
                .onItem().invoke(brandEntity -> {
                    if (brandEntity == null) {
                        LOGGER.errorf("%s Brand entity is null for slug: %s", logPrefix(), brand);
                        throw new IllegalStateException("Brand not found: " + brand);
                    }
                    brandId = brandEntity.getId();
                    LOGGER.infof("%s Brand ID resolved successfully: %s", logPrefix(), brandId);
                })
                .onFailure().transform(e -> {
                    LOGGER.errorf(e, "%s CRITICAL: Failed to resolve brand ID for slug: %s", logPrefix(), brand);
                    return new RuntimeException("Cannot resolve brand ID for: " + brand, e);
                })
                .replaceWithVoid();
    }

    private Uni<Boolean> addFragmentToQueue(SoundFragment soundFragment) {
        LiveSoundFragment liveSoundFragment = new LiveSoundFragment();
        SongMetadata songMetadata = new SongMetadata(
                soundFragment.getId(),
                soundFragment.getTitle(),
                soundFragment.getArtist()
        );
        liveSoundFragment.setSoundFragmentId(soundFragment.getId());
        liveSoundFragment.setMetadata(songMetadata);

        LOGGER.infof("%s Starting to process fragment: %s - %s", logPrefix(), soundFragment.getTitle(), soundFragment.getArtist());
        
        return fileHandler.getFirstFile(soundFragment.getId())
                .ifNoItem().after(Duration.ofSeconds(30)).fail()
                .onFailure().recoverWithUni(ex -> {
                    LOGGER.warnf("%s Failed to retrieve file metadata for fragment %s: %s", logPrefix(), soundFragment.getId(), ex.getMessage());
                    return Uni.createFrom().item((FileMetadata) null);
                })
                .onItem().invoke(fileMetadata -> {
                    if (fileMetadata != null) {
                        LOGGER.infof("%s Retrieved file metadata: %s (size: %d bytes)", 
                                logPrefix(), fileMetadata.getFileOriginalName(), 
                                fileMetadata.getContentLength() != null ? fileMetadata.getContentLength() : 0);
                    }
                })
                .onItem().transformToUni(fileMetadata -> {
                    if (fileMetadata == null) {
                        LOGGER.warnf("%s No file found for fragment: %s", logPrefix(), soundFragment.getId());
                        return Uni.createFrom().item(false);
                    }

                    LOGGER.infof("%s Starting materialization for: %s", logPrefix(), fileMetadata.getFileOriginalName());
                    
                    return fileMetadata.materializeFileStream(tempDir.toString())
                            .ifNoItem().after(Duration.ofMinutes(5)).fail()
                            .onFailure().invoke(e -> {
                                LOGGER.errorf(e, "%s Materialization FAILED for %s: %s", 
                                        logPrefix(), fileMetadata.getFileOriginalName(), e.getMessage());
                            })
                            .onItem().invoke(tempFile -> {
                                LOGGER.infof("%s Materialization complete: %s", logPrefix(), tempFile);
                            })
                            .onItem().transformToUni(tempFile -> {
                                LOGGER.infof("%s Starting segmentation for: %s", logPrefix(), songMetadata.getTitle());
                                long[] bitrates = {128000L, 64000L};
                                return segmentationService.slice(songMetadata, tempFile, List.of(bitrates[0], bitrates[1]))
                                        .ifNoItem().after(Duration.ofMinutes(3)).fail()
                                        .onFailure().invoke(e -> {
                                            LOGGER.errorf(e, "%s Segmentation FAILED for %s: %s", 
                                                    logPrefix(), songMetadata.getTitle(), e.getMessage());
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
                                                LOGGER.warnf("%s No segments created for fragment: %s", logPrefix(), soundFragment.getId());
                                                return Uni.createFrom().item(false);
                                            }

                                            liveSoundFragment.setSegments(segments);
                                            playlistState.regularQueue.add(liveSoundFragment);

                                            LOGGER.infof("%s ✓ Successfully added fragment to queue: %s - %s (%d segments)", 
                                                    logPrefix(), songMetadata.getTitle(), songMetadata.getArtist(), 
                                                    segments.values().stream().findFirst().map(ConcurrentLinkedQueue::size).orElse(0));
                                            return Uni.createFrom().item(true);
                                        });
                            })
                            .onFailure().transform(e -> {
                                LOGGER.errorf(e, "%s Failed to materialize/segment file: %s",
                                        logPrefix(), fileMetadata.getFileOriginalName());
                                return new RuntimeException("Materialization failed", e);
                            });
                });
    }

    public LiveSoundFragment getNextLiveFragment() {
        if (!initialized) {
            LOGGER.infof("%s Not initialized, triggering lazy initialization", logPrefix());
            ensureInitialized().await().indefinitely();
        }
        
        LOGGER.debugf("%s Queues: prioritized=%d, regular=%d",
                logPrefix(),
                playlistState.prioritizedQueue.size(),
                playlistState.regularQueue.size());

        if (!playlistState.prioritizedQueue.isEmpty()) {
            LiveSoundFragment next = playlistState.prioritizedQueue.poll();
            moveFragmentToProcessedList(next);
            LOGGER.debugf("%s Returned prioritized: %s", logPrefix(), next.getMetadata());
            return next;
        }

        if (!playlistState.regularQueue.isEmpty()) {
            LiveSoundFragment next = playlistState.regularQueue.poll();
            moveFragmentToProcessedList(next);
            LOGGER.debugf("%s Returned regular: %s", logPrefix(), next.getMetadata());
            return next;
        }

        LOGGER.warnf("%s Queues empty, activating waiting audio", logPrefix());
        feedFragments(1, true);

        if (waitingAudioProvider.isWaitingAudioAvailable()) {
            return waitingAudioProvider.createWaitingFragment()
                    .await().atMost(Duration.ofSeconds(5));
        } else {
            return null;
        }
    }

    private void moveFragmentToProcessedList(LiveSoundFragment fragment) {
        if (fragment == null) return;

        slicedFragmentsLock.writeLock().lock();
        try {
            playlistState.obtainedByHlsPlaylist.add(fragment);
            playlistState.fragmentsForMp3.add(fragment);

            while (playlistState.fragmentsForMp3.size() > 2) {
                playlistState.fragmentsForMp3.removeFirst();
            }

            LOGGER.debugf("%s Queued fragment: %s (processed: %d)",
                    logPrefix(),fragment.getMetadata(),
                    playlistState.obtainedByHlsPlaylist.size());

            if (playlistState.obtainedByHlsPlaylist.size() > PROCESSED_QUEUE_MAX_SIZE) {
                LiveSoundFragment removed = playlistState.obtainedByHlsPlaylist.poll();
                LOGGER.tracef("%s Trimmed processed queue, removed: %s",logPrefix(), removed.getMetadata());
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
            playlistState.fragmentsForMp3.clear();
        } finally {
            slicedFragmentsLock.writeLock().unlock();
        }

        playlistState.regularQueue.clear();
        playlistState.prioritizedQueue.clear();

        LOGGER.infof("%s Shutdown complete. All queues cleared.", logPrefix());
    }

    private String logPrefix() {
        return "[" + brand + "]";
    }
}