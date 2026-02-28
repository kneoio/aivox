package com.semantyca.aivox.service.playlist;

import com.semantyca.aivox.config.AivoxConfig;
import com.semantyca.aivox.config.HlsConfig;
import com.semantyca.aivox.service.AudioFile;
import com.semantyca.aivox.streaming.HlsSegment;
import com.semantyca.aivox.streaming.LiveSoundFragment;
import com.semantyca.aivox.streaming.SongMetadata;
import com.semantyca.aivox.streaming.WaitingAudioProvider;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
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
    private final HlsConfig hlsConfig;
    private final com.semantyca.aivox.service.RadioDJProcessor radioDJProcessor;
    private final WaitingAudioProvider waitingAudioProvider;

    @Inject
    public PlaylistManager(AivoxConfig aivoxConfig, HlsConfig hlsConfig, com.semantyca.aivox.service.RadioDJProcessor radioDJProcessor, WaitingAudioProvider waitingAudioProvider) {
        this.aivoxConfig = aivoxConfig;
        this.hlsConfig = hlsConfig;
        this.radioDJProcessor = radioDJProcessor;
        this.waitingAudioProvider = waitingAudioProvider;

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
        LOGGER.info("Adding " + quantityToFetch + " fragments for brand " + brand);

        // Create placeholder fragments synchronously for immediate availability
        for (int i = 0; i < quantityToFetch; i++) {
            LiveSoundFragment fragment = createPlaceholderFragmentSync(brand);
            if (fragment != null) {
                state.regularQueue.offer(fragment);
                LOGGER.info("Added placeholder fragment to regular queue for brand: " + brand);
            }
        }
    }

    private LiveSoundFragment createPlaceholderFragmentSync(String brand) {
        if (waitingAudioProvider.isWaitingAudioAvailable()) {
            LOGGER.debug("Using waiting audio for brand: " + brand);
            return waitingAudioProvider.createWaitingFragment()
                .await().atMost(java.time.Duration.ofSeconds(5));
        } else {
            // Fallback to creating a simple placeholder
            LOGGER.warn("Waiting audio not available, using simple placeholder for brand: " + brand);
            LiveSoundFragment fragment = new LiveSoundFragment();
            fragment.setSoundFragmentId(UUID.randomUUID());
            fragment.setMetadata(new SongMetadata(fragment.getSoundFragmentId(), "Placeholder Song", "Placeholder Artist"));
            
            // Create placeholder segments
            Map<Long, ConcurrentLinkedQueue<HlsSegment>> segments = new HashMap<>();
            long[] bitrates = {128000L, 64000L};
            
            for (long bitrate : bitrates) {
                ConcurrentLinkedQueue<HlsSegment> segmentQueue = new ConcurrentLinkedQueue<>();
                // Create a few placeholder segments
                for (int i = 0; i < 3; i++) {
                    HlsSegment segment = new HlsSegment();
                    segment.setSequence(i);
                    segment.setDuration(6); // 6 seconds
                    segment.setData(new byte[1024]); // Placeholder data
                    segment.setSongMetadata(fragment.getMetadata());
                    segmentQueue.offer(segment);
                }
                segments.put(bitrate, segmentQueue);
            }
            
            fragment.setSegments(segments);
            return fragment;
        }
    }

    private Uni<LiveSoundFragment> createPlaceholderFragment(String brand) {
        return Uni.createFrom().item(() -> createPlaceholderFragmentSync(brand));
    }

    public List<AudioFile> getNextAudioFiles(String brand) {
        LOGGER.info("getNextAudioFiles called for brand: " + brand);
        PlaylistState state = playlistStates.get(brand);
        if (state == null) {
            LOGGER.warn("No playlist state found for brand: " + brand + ", creating new state");
            state = new PlaylistState();
            playlistStates.put(brand, state);
        }

        LiveSoundFragment fragment = getNextFragment(brand);
        LOGGER.info("getNextFragment returned: " + (fragment != null ? "fragment with ID " + fragment.getSoundFragmentId() : "NULL"));
        if (fragment == null) {
            return Collections.emptyList();
        }

        // Convert LiveSoundFragment to AudioFile
        List<AudioFile> audioFiles = new ArrayList<>();
        Map<Long, ConcurrentLinkedQueue<HlsSegment>> segments = fragment.getSegments();
        LOGGER.info("Fragment has " + (segments != null ? segments.size() : 0) + " bitrate variants");
        
        if (segments != null && !segments.isEmpty()) {
            // Get the highest bitrate segments
            long maxBitrate = segments.keySet().stream().max(Long::compare).orElse(128000L);
            ConcurrentLinkedQueue<HlsSegment> segmentQueue = segments.get(maxBitrate);
            
            if (segmentQueue != null) {
                LOGGER.info("SegmentQueue for bitrate " + maxBitrate + " has " + segmentQueue.size() + " segments");
                for (HlsSegment segment : segmentQueue) {
                    AudioFile audioFile = new AudioFile(segment.getData(), "mp3", fragment.getSoundFragmentId());
                    audioFiles.add(audioFile);
                }
            }
        }

        LOGGER.info("Returning " + audioFiles.size() + " audio files for brand: " + brand);
        return audioFiles;
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
        
        LOGGER.warn("Both queues are empty for brand: " + brand);

        // If both queues are empty, try to feed more fragments or use waiting audio
        if (waitingAudioProvider.isWaitingAudioAvailable()) {
            LOGGER.debug("Both queues empty for brand " + brand + ", using waiting audio");
            return waitingAudioProvider.createWaitingFragment()
                .await().indefinitely();
        } else {
            LOGGER.warn("Both queues empty for brand " + brand + ", creating emergency placeholder");
            // Create emergency placeholder fragment with segments
            LiveSoundFragment emergencyFragment = new LiveSoundFragment();
            emergencyFragment.setSoundFragmentId(UUID.randomUUID());
            emergencyFragment.setMetadata(new SongMetadata(emergencyFragment.getSoundFragmentId(), "Loading...", "Station"));
            
            Map<Long, ConcurrentLinkedQueue<HlsSegment>> segments = new HashMap<>();
            long[] bitrates = {128000L, 64000L};
            for (long bitrate : bitrates) {
                ConcurrentLinkedQueue<HlsSegment> segmentQueue = new ConcurrentLinkedQueue<>();
                for (int i = 0; i < 3; i++) {
                    HlsSegment segment = new HlsSegment();
                    segment.setSequence(i);
                    segment.setDuration(6);
                    segment.setData(new byte[1024]);
                    segment.setSongMetadata(emergencyFragment.getMetadata());
                    segmentQueue.offer(segment);
                }
                segments.put(bitrate, segmentQueue);
            }
            emergencyFragment.setSegments(segments);
            return emergencyFragment;
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

    public void initializeBrand(String brand) {
        PlaylistState state = playlistStates.computeIfAbsent(brand, k -> {
            LOGGER.info("Initializing playlist for brand: " + brand);
            return new PlaylistState();
        });
        
        // Feed initial fragments after state is created
        if (state.regularQueue.isEmpty() && state.prioritizedQueue.isEmpty()) {
            feedFragments(brand, REGULAR_BUFFER_MAX, false);
            LOGGER.info("After initial feed, regularQueue size: " + state.regularQueue.size());
        }
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
