package com.semantyca.aivox.streaming;

import com.semantyca.aivox.config.HlsConfig;
import com.semantyca.aivox.service.playlist.PlaylistManager;
import io.smallrye.mutiny.subscription.Cancellable;
import org.jboss.logging.Logger;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamManager {
    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Lisbon");
    private static final Logger LOGGER = Logger.getLogger(StreamManager.class);
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([^_]+)_([0-9]+)_([0-9]+)\\.ts$");

    private final AtomicLong currentSequence = new AtomicLong(0);
    private static final int SEGMENTS_TO_DRIP_PER_FEED_CALL = 1;

    private final String brand;
    private final StreamState streamState = new StreamState();
    private final PlaylistManager playlistManager;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final HlsConfig hlsConfig;
    private final SegmentFeederTimer segmentFeederTimer;
    private final SliderTimer sliderTimer;

    private Cancellable feederSubscription;
    private Cancellable sliderSubscription;

    public StreamManager(String brand, PlaylistManager playlistManager, HlsConfig hlsConfig,
                         SegmentFeederTimer segmentFeederTimer, SliderTimer sliderTimer) {
        this.brand = brand;
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
        if (streamState.liveSegments.isEmpty()) {
            LOGGER.warn("liveSegments is EMPTY for brand: " + brand + ", pendingQueue size: " + streamState.pendingQueue.size());
            return getDefaultPlaylist();
        }

        final long targetBitrate = bitrate != null ? bitrate : 128000L;
        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-ALLOW-CACHE:NO\n")
                .append("#EXT-X-PLAYLIST-TYPE:EVENT\n")  // CRITICAL - DO NOT REMOVE
                .append("#EXT-X-TARGETDURATION:").append(hlsConfig.getSegmentDuration()).append("\n");

        long firstSequenceInWindow = streamState.liveSegments.firstKey();
        playlist.append("#EXT-X-MEDIA-SEQUENCE:").append(firstSequenceInWindow).append("\n");
        playlist.append("#EXT-X-PROGRAM-DATE-TIME:")
                .append(ZonedDateTime.now(ZONE_ID).format(DateTimeFormatter.ISO_INSTANT))
                .append("\n");

        streamState.liveSegments.tailMap(firstSequenceInWindow).entrySet().stream()
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
                                .append(segment.getSongMetadata() != null ?
                                        segment.getSongMetadata().getTitle() + " - " + segment.getSongMetadata().getArtist() :
                                        "")
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
            
            Map<Long, HlsSegment> bitrateSlot = streamState.liveSegments.get(sequence);
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

    private void feedSegments() {
        LOGGER.info("feedSegments called for " + brand + ", pendingQueue: " + streamState.pendingQueue.size() + ", liveSegments: " + streamState.liveSegments.size());
        
        if (!streamState.pendingQueue.isEmpty()) {
            for (int i = 0; i < SEGMENTS_TO_DRIP_PER_FEED_CALL; i++) {
                if (streamState.liveSegments.size() >= hlsConfig.getMaxVisibleSegments() * 2) {
                    LOGGER.debug("Live segments buffer for " + brand + " is full (" + streamState.liveSegments.size() + "/" + (hlsConfig.getMaxVisibleSegments() * 2) + "). Pausing drip-feed.");
                    break;
                }
                Map<Long, HlsSegment> bitrateSlot = streamState.pendingQueue.poll();
                if (bitrateSlot != null) {
                    long seq = bitrateSlot.values().iterator().next().getSequence();
                    streamState.liveSegments.put(seq, bitrateSlot);
                    LOGGER.info("Moved segment seq=" + seq + " from pending to live for brand: " + brand);
                }
            }
        }

        if (streamState.pendingQueue.size() < PENDING_QUEUE_REFILL_THRESHOLD) {
            LOGGER.info("Pending queue below threshold (" + streamState.pendingQueue.size() + " < " + PENDING_QUEUE_REFILL_THRESHOLD + "), fetching from PlaylistManager for brand: " + brand);
            try {
                // Get next fragment from playlist manager with real HLS segments
                LiveSoundFragment fragment = playlistManager.getNextLiveFragment();
                if (fragment != null) {
                    LOGGER.info("PlaylistManager returned fragment with ID: " + fragment.getSoundFragmentId());
                    addFragmentToPendingQueue(fragment);
                } else {
                    LOGGER.warn("PlaylistManager returned null fragment for brand: " + brand);
                }
            } catch (Exception e) {
                LOGGER.error("Error feeding segments for brand " + brand + ": " + e.getMessage(), e);
            }
        }
    }

    private void addFragmentToPendingQueue(LiveSoundFragment fragment) {
        Map<Long, ConcurrentLinkedQueue<HlsSegment>> segments = fragment.getSegments();
        if (segments == null || segments.isEmpty()) {
            LOGGER.warn("Fragment has no segments: " + fragment.getSoundFragmentId());
            return;
        }

        LOGGER.info("Adding fragment " + fragment.getSoundFragmentId() + " with " + segments.size() + " bitrate variants to pending queue");

        // Get the first bitrate to determine how many segments we have
        ConcurrentLinkedQueue<HlsSegment> firstBitrateQueue = segments.values().iterator().next();
        int segmentCount = firstBitrateQueue.size();

        LOGGER.info("Fragment has " + segmentCount + " segments per bitrate");

        // Convert from Map<Bitrate, Queue<Segment>> to Queue<Map<Bitrate, Segment>>
        // This reorganizes segments so each queue entry has all bitrates for one segment
        for (int i = 0; i < segmentCount; i++) {
            long globalSeq = currentSequence.getAndIncrement();
            Map<Long, HlsSegment> bitrateSlot = new HashMap<>();

            for (Map.Entry<Long, ConcurrentLinkedQueue<HlsSegment>> entry : segments.entrySet()) {
                Long bitrate = entry.getKey();
                ConcurrentLinkedQueue<HlsSegment> queue = entry.getValue();

                HlsSegment segment = queue.poll();
                if (segment != null) {
                    // Update sequence number to be globally unique
                    segment.setSequence(globalSeq);
                    bitrateSlot.put(bitrate, segment);
                }
            }

            if (!bitrateSlot.isEmpty()) {
                streamState.pendingQueue.offer(bitrateSlot);
                //long seq = bitrateSlot.values().iterator().next().getSequence();
                //LOGGER.info("Added segment seq=" + seq + " to pendingQueue, new size: " + streamState.pendingQueue.size());
            }
        }
    }

    private void slideWindow() {
        if (streamState.liveSegments.isEmpty()) {
            return;
        }
        
        while (streamState.liveSegments.size() > hlsConfig.getMaxVisibleSegments()) {
            streamState.liveSegments.pollFirstEntry();
        }
    }

    public void initializeStream() {
        LOGGER.info("Initializing stream for brand: " + brand);

        segmentFeederTimer.setDurationSec(hlsConfig.getSegmentDuration());

        feederSubscription = segmentFeederTimer.getTicker().subscribe().with(
                timestamp -> executorService.submit(this::feedSegments),
                error -> LOGGER.error("Feeder subscription error for " + brand + ": " + error.getMessage(), error)
        );

        sliderSubscription = sliderTimer.getTicker().subscribe().with(
                timestamp -> executorService.submit(this::slideWindow),
                error -> LOGGER.error("Slider subscription error for " + brand + ": " + error.getMessage(), error)
        );
    }

    public void shutdown() {
        LOGGER.info("Shutting down stream for brand: " + brand);
        
        // Cancel timer subscriptions
        if (feederSubscription != null) {
            feederSubscription.cancel();
        }
        if (sliderSubscription != null) {
            sliderSubscription.cancel();
        }
        
        // Shutdown executor service
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.error("Executor service did not terminate for brand: " + brand);
                }
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        streamState.liveSegments.clear();
        streamState.pendingQueue.clear();
        LOGGER.info("Stream shutdown complete for brand: " + brand);
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
