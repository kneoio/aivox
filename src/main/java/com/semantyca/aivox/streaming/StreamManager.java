package com.semantyca.aivox.streaming;

import com.semantyca.aivox.config.HlsConfig;
import com.semantyca.aivox.service.AudioFile;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
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

    @Inject
    public StreamManager(PlaylistManager playlistManager, HlsConfig hlsConfig) {
        this.playlistManager = playlistManager;
        this.hlsConfig = hlsConfig;
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
            return getDefaultPlaylist();
        }

        if (state.liveSegments.isEmpty()) {
            return getDefaultPlaylist();
        }

        final long targetBitrate = bitrate != null ? bitrate : 128000L;
        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-ALLOW-CACHE:NO\n")
                .append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
                .append("#EXT-X-TARGETDURATION:").append(hlsConfig.getSegmentDuration()).append("\n");

        long firstSequenceInWindow = state.liveSegments.firstKey();
        playlist.append("#EXT-X-MEDIA-SEQUENCE:").append(firstSequenceInWindow).append("\n");
        playlist.append("#EXT-X-PROGRAM-DATE-TIME:")
                .append(ZonedDateTime.now(ZONE_ID).format(DateTimeFormatter.ISO_INSTANT))
                .append("\n");

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
                                .append("segments/")
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
                }
            }
        }

        if (state.pendingQueue.size() < PENDING_QUEUE_REFILL_THRESHOLD) {
            try {
                // Get next fragment from playlist manager
                List<AudioFile> audioFiles = playlistManager.getNextAudioFiles(brand);
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
        }
    }

    public void shutdownStream(String brand) {
        String brandKey = brand.toLowerCase();
        StreamState state = streamStates.remove(brandKey);
        if (state != null) {
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
