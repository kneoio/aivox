package com.semantyca.aivox.streaming;

import com.semantyca.aivox.service.AudioFile;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
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

@ApplicationScoped
public class PlaylistManager {
    private static final Logger LOGGER = Logger.getLogger(PlaylistManager.class);
    private static final int SELF_MANAGING_INTERVAL_SECONDS = 100;
    private static final int REGULAR_BUFFER_MAX = 2;
    private static final int TRIGGER_SELF_MANAGING = 2;
    private static final int PROCESSED_QUEUE_MAX_SIZE = 2;
    private static final long STARVING_FEED_COOLDOWN_MILLIS = 20_000L;

    private final ReadWriteLock slicedFragmentsLock = new ReentrantReadWriteLock();

    // Brand-specific playlist state
    private final Map<String, PlaylistState> playlistStates = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private volatile long lastStarvingFeedTime = 0;

    @Inject
    com.semantyca.aivox.service.RadioDJProcessor radioDJProcessor;

    public PlaylistManager() {
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

        // In a real implementation, this would fetch from a database or external service
        // For now, we'll simulate with placeholder fragments
        for (int i = 0; i < quantityToFetch; i++) {
            LiveSoundFragment fragment = createPlaceholderFragment(brand);
            state.regularQueue.offer(fragment);
        }
    }

    private LiveSoundFragment createPlaceholderFragment(String brand) {
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

    public List<AudioFile> getNextAudioFiles(String brand) {
        PlaylistState state = playlistStates.get(brand);
        if (state == null) {
            state = new PlaylistState();
            playlistStates.put(brand, state);
        }

        LiveSoundFragment fragment = getNextFragment(brand);
        if (fragment == null) {
            return Collections.emptyList();
        }

        // Convert LiveSoundFragment to AudioFile
        List<AudioFile> audioFiles = new ArrayList<>();
        Map<Long, ConcurrentLinkedQueue<HlsSegment>> segments = fragment.getSegments();
        
        if (segments != null && !segments.isEmpty()) {
            // Get the highest bitrate segments
            long maxBitrate = segments.keySet().stream().max(Long::compare).orElse(128000L);
            ConcurrentLinkedQueue<HlsSegment> segmentQueue = segments.get(maxBitrate);
            
            if (segmentQueue != null) {
                for (HlsSegment segment : segmentQueue) {
                    AudioFile audioFile = new AudioFile(segment.getData(), "mp3", fragment.getSoundFragmentId());
                    audioFiles.add(audioFile);
                }
            }
        }

        return audioFiles;
    }

    private LiveSoundFragment getNextFragment(String brand) {
        PlaylistState state = playlistStates.get(brand);
        if (state == null) {
            return null;
        }

        // Check prioritized queue first
        if (!state.prioritizedQueue.isEmpty()) {
            LiveSoundFragment nextFragment = state.prioritizedQueue.poll();
            moveFragmentToProcessedList(brand, nextFragment);
            return nextFragment;
        }

        // Then check regular queue
        if (!state.regularQueue.isEmpty()) {
            LiveSoundFragment nextFragment = state.regularQueue.poll();
            moveFragmentToProcessedList(brand, nextFragment);
            return nextFragment;
        }

        // If both queues are empty, try to feed more fragments
        feedFragments(brand, 1, true);
        
        return null;
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
        playlistStates.computeIfAbsent(brand, k -> {
            LOGGER.info("Initialized playlist for brand: " + brand);
            return new PlaylistState();
        });
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
