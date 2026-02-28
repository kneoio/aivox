package com.semantyca.aivox.streaming;

import com.semantyca.aivox.config.HlsConfig;
import com.semantyca.aivox.service.AudioFile;
import com.semantyca.aivox.service.playlist.PlaylistManager;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import io.smallrye.mutiny.subscription.Cancellable;
import org.jboss.logging.Logger;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Dependent
public class StreamManager {
    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Lisbon");
    private static final Logger LOGGER = Logger.getLogger(StreamManager.class);
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([^_]+)_([0-9]+)_([0-9]+)\\.ts$");

    private final AtomicLong currentSequence = new AtomicLong(0);
    private static final int SEGMENTS_TO_DRIP_PER_FEED_CALL = 1;

    private final Map<String, StreamState> streamStates = new ConcurrentHashMap<>();
    private final PlaylistManager playlistManager;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final HlsConfig hlsConfig;
    private final SegmentFeederTimer segmentFeederTimer;
    private final SliderTimer sliderTimer;

    private final Map<String, Cancellable> timerSubscriptions = new ConcurrentHashMap<>();

    @Inject
    public StreamManager(PlaylistManager playlistManager, HlsConfig hlsConfig,
                         SegmentFeederTimer segmentFeederTimer, SliderTimer sliderTimer) {
        this.playlistManager = playlistManager;
        this.hlsConfig = hlsConfig;
        this.segmentFeederTimer = segmentFeederTimer;
        this.sliderTimer = sliderTimer;
    }

    private static final int PENDING_QUEUE_REFILL_THRESHOLD = 10;

    public String generateMasterPlaylist(String brand) {

        long maxRate = 128000L;
        long halfRate = maxRate / 2;

        return "#EXTM3U\n" +

                // Support multiple bitrates
                // Default bitrate
                "#EXT-X-STREAM-INF:BANDWIDTH=" + maxRate + "\n" +
                "/api/stream/" + brand.toLowerCase() + "/stream.m3u8?bitrate=" + maxRate + "\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=" + halfRate + "\n" +
                "/api/stream/" + brand.toLowerCase() + "/stream.m3u8?bitrate=" + halfRate + "\n";
    }

    public String generatePlaylist(String brand, Long bitrate) {
        StreamState state = streamStates.get(brand.toLowerCase());
        if (state == null) {
            LOGGER.warn("No stream state for brand: " + brand);
            return getDefaultPlaylist();
        }

        if (state.liveSegments.isEmpty()) {
            LOGGER.warn("liveSegments is EMPTY for brand: " + brand + ", pendingQueue size: " + state.pendingQueue.size());
            return getDefaultPlaylist();
        }
        
        LOGGER.info("Generating playlist for brand: " + brand + ", liveSegments: " + state.liveSegments.size() + ", pendingQueue: " + state.pendingQueue.size());

        final long targetBitrate = bitrate != null ? bitrate : 128000L;
        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-TARGETDURATION:").append(hlsConfig.getSegmentDuration()).append("\n");

        long firstSequenceInWindow = state.liveSegments.firstKey();
        playlist.append("#EXT-X-MEDIA-SEQUENCE:").append(firstSequenceInWindow).append("\n");

        state.liveSegments.tailMap(firstSequenceInWindow).entrySet().stream()
                .limit(hlsConfig.getMaxVisibleSegments())
                .forEach(entry -> {
                    Map<Long, HlsSegment> bitrateSlot = entry.getValue();
                    HlsSegment segment = bitrateSlot.containsKey(targetBitrate)
                            ? bitrateSlot.get(targetBitrate)
                            : bitrateSlot.values().iterator().next();
                    
                    if (segment != null) {
                        playlist.append("#EXTINF:")
                                .append(segment.getDuration())
                                .append(",")
                                .append(segment.getSongMetadata() != null ? segment.getSongMetadata().toString() : "")
                                .append("\n")
                                .append("/api/stream/")
                                .append(brand.toLowerCase())
                                .append("/segments/")
                                .append(brand.toLowerCase())
                                .append("_")
                                .append(targetBitrate)
                                .append("_")
                                .append(segment.getSequence())
                                .append(".ts\n");
                    }
                });

        return playlist.toString();
    }

    public HlsSegment getSegment(String brand, String segmentFile) {
        try {
            Matcher matcher = SEGMENT_PATTERN.matcher(segmentFile);
            if (!matcher.find()) {
                LOGGER.warn("Segment '" + segmentFile + "' doesn't match expected pattern: " + SEGMENT_PATTERN.pattern());
                return null;
            }
            
            String brandFromSegment = matcher.group(1);
            if (!brandFromSegment.equalsIgnoreCase(brand)) {
                LOGGER.warn("Segment brand '" + brandFromSegment + "' doesn't match requested brand '" + brand + "'");
                return null;
            }
            
            long bitrate = Long.parseLong(matcher.group(2));
            long sequence = Long.parseLong(matcher.group(3));
            
            StreamState state = streamStates.get(brand.toLowerCase());
            if (state == null) {
                LOGGER.debug("No stream state found for brand: " + brand);
                return null;
            }
            
            Map<Long, HlsSegment> bitrateSlot = state.liveSegments.get(sequence);
            if (bitrateSlot == null) {
                LOGGER.debug("Segment sequence " + sequence + " not found in liveSegments");
                return null;
            }
            
            HlsSegment segment = bitrateSlot.get(bitrate);
            if (segment == null) {
                LOGGER.debug("Bitrate " + bitrate + " not found for sequence " + sequence);
                // Try to return any available bitrate as fallback
                if (!bitrateSlot.isEmpty()) {
                    segment = bitrateSlot.values().iterator().next();
                }
            }
            
            return segment;
        } catch (Exception e) {
            LOGGER.warn("Error processing segment request '" + segmentFile + "' for brand '" + brand + "': " + e.getMessage(), e);
            return null;
        }
    }

    // Note: Scheduled methods removed since StreamManager is now @Dependent
    // The scheduling is handled by the RadioStationPool or should be moved to a separate @ApplicationScoped service
    public void feedSegments() {
        executorService.submit(() -> {
            for (Map.Entry<String, StreamState> entry : streamStates.entrySet()) {
                String brand = entry.getKey();
                StreamState state = entry.getValue();
                feedSegmentsForBrand(brand, state);
            }
        });
    }

    private void feedSegmentsForBrand(String brand, StreamState state) {
        LOGGER.info("feedSegmentsForBrand called for " + brand + ", pendingQueue: " + state.pendingQueue.size() + ", liveSegments: " + state.liveSegments.size());
        
        if (!state.pendingQueue.isEmpty()) {
            for (int i = 0; i < SEGMENTS_TO_DRIP_PER_FEED_CALL; i++) {
                if (state.liveSegments.size() >= hlsConfig.getMaxVisibleSegments() * 2) {
                    LOGGER.debug("Live segments buffer for " + brand + " is full (" + state.liveSegments.size() + "/" + (hlsConfig.getMaxVisibleSegments() * 2) + "). Pausing drip-feed.");
                    break;
                }
                Map<Long, HlsSegment> bitrateSlot = state.pendingQueue.poll();
                if (bitrateSlot != null) {
                    long seq = bitrateSlot.values().iterator().next().getSequence();
                    state.liveSegments.put(seq, bitrateSlot);
                    LOGGER.info("Moved segment seq=" + seq + " from pending to live for brand: " + brand);
                }
            }
        }

        if (state.pendingQueue.size() < PENDING_QUEUE_REFILL_THRESHOLD) {
            LOGGER.info("Pending queue below threshold (" + state.pendingQueue.size() + " < " + PENDING_QUEUE_REFILL_THRESHOLD + "), fetching from PlaylistManager for brand: " + brand);
            try {
                // Get next fragment from playlist manager
                List<AudioFile> audioFiles = playlistManager.getNextAudioFiles(brand);
                LOGGER.info("PlaylistManager returned " + (audioFiles != null ? audioFiles.size() : 0) + " audio files for brand: " + brand);
                if (audioFiles != null && !audioFiles.isEmpty()) {
                    createSegmentsFromAudioFiles(brand, audioFiles, state);
                }
            } catch (Exception e) {
                LOGGER.error("Error feeding segments for brand " + brand + ": " + e.getMessage(), e);
            }
        }
    }

    private void createSegmentsFromAudioFiles(String brand, List<AudioFile> audioFiles, StreamState state) {
        // Simulate segment creation from audio files
        // In a real implementation, this would use AudioSegmentationService
        LOGGER.info("Creating segments from " + audioFiles.size() + " audio files for brand: " + brand);
        for (AudioFile audioFile : audioFiles) {
            long seq = currentSequence.getAndIncrement();
            Map<Long, HlsSegment> bitrateSlot = new HashMap<>();
            
            // Create segments for different bitrates
            long[] bitrates = {128000L, 64000L}; // High and low quality
            for (long bitrate : bitrates) {
                HlsSegment segment = new HlsSegment();
                segment.setSequence(seq);
                segment.setDuration(hlsConfig.getSegmentDuration());
                segment.setData(audioFile.getData()); // In real implementation, this would be segmented audio
                segment.setSongMetadata(new SongMetadata(audioFile.getSongId(), "Generated Song", "Generated Artist"));
                bitrateSlot.put(bitrate, segment);
            }
            
            state.pendingQueue.offer(bitrateSlot);
            LOGGER.info("Added segment seq=" + seq + " to pendingQueue for brand: " + brand + ", new pendingQueue size: " + state.pendingQueue.size());
        }
    }

    // Note: Scheduled methods removed since StreamManager is now @Dependent
    public void slideWindow() {
        executorService.submit(() -> {
            for (Map.Entry<String, StreamState> entry : streamStates.entrySet()) {
                String brand = entry.getKey();
                StreamState state = entry.getValue();
                slideWindowForBrand(brand, state);
            }
        });
    }

    private void slideWindowForBrand(String brand, StreamState state) {
        if (state.liveSegments.isEmpty()) {
            return;
        }
        
        while (state.liveSegments.size() > hlsConfig.getMaxVisibleSegments()) {
            state.liveSegments.pollFirstEntry();
        }
    }

    public void initializeStream(String brand) {
        String brandKey = brand.toLowerCase();
        if (!streamStates.containsKey(brandKey)) {
            StreamState state = new StreamState();
            streamStates.put(brandKey, state);
            LOGGER.info("Initialized stream for brand: " + brand);

            // Start timers for this stream
            segmentFeederTimer.setDurationSec(hlsConfig.getSegmentDuration());

            Cancellable feeder = segmentFeederTimer.getTicker().subscribe().with(
                    timestamp -> executorService.submit(this::feedSegments),
                    error -> LOGGER.error("Feeder subscription error for " + brand + ": " + error.getMessage(), error)
            );

            Cancellable slider = sliderTimer.getTicker().subscribe().with(
                    timestamp -> executorService.submit(this::slideWindow),
                    error -> LOGGER.error("Slider subscription error for " + brand + ": " + error.getMessage(), error)
            );

            timerSubscriptions.put(brandKey + "_feeder", feeder);
            timerSubscriptions.put(brandKey + "_slider", slider);
        }
    }

    public void shutdownStream(String brand) {
        String brandKey = brand.toLowerCase();
        StreamState state = streamStates.remove(brandKey);
        if (state != null) {
            // Cancel timer subscriptions
            Cancellable feeder = timerSubscriptions.remove(brandKey + "_feeder");
            Cancellable slider = timerSubscriptions.remove(brandKey + "_slider");
            if (feeder != null) feeder.cancel();
            if (slider != null) slider.cancel();

            state.liveSegments.clear();
            state.pendingQueue.clear();
            LOGGER.info("Shutdown stream for brand: " + brand);
        }
    }

    private String getDefaultPlaylist() {
        return "#EXTM3U\n" +
                "#EXT-X-VERSION:3\n" +
                "#EXT-X-ALLOW-CACHE:NO\n" +
                "#EXT-X-TARGETDURATION:" + hlsConfig.getSegmentDuration() + "\n" +
                "#EXT-X-MEDIA-SEQUENCE:0\n";
    }

    private static class StreamState {
        final ConcurrentSkipListMap<Long, Map<Long, HlsSegment>> liveSegments = new ConcurrentSkipListMap<>();
        final Queue<Map<Long, HlsSegment>> pendingQueue = new ConcurrentLinkedQueue<>();
    }
}
