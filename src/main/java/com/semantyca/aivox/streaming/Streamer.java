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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Streamer {
    private static final ZoneId ZONE_ID = ZoneId.of("Europe/Lisbon");
    private static final Logger LOGGER = Logger.getLogger(Streamer.class);
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([^_]+)_([0-9]+)_([0-9]+)\\.ts$");
    private static final int PENDING_QUEUE_REFILL_THRESHOLD = 10;

    private final AtomicLong currentSequence = new AtomicLong(0);
    private final Matcher segmentMatcher = SEGMENT_PATTERN.matcher("");

    private final String brand;
    private final StreamState streamState = new StreamState();
    private final PlaylistManager playlistManager;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final HlsConfig hlsConfig;
    private final SegmentFeederTimer segmentFeederTimer;
    private final SliderTimer sliderTimer;

    private Cancellable feederSubscription;
    private Cancellable sliderSubscription;

    public Streamer(String brand, PlaylistManager playlistManager, HlsConfig hlsConfig,
                    SegmentFeederTimer segmentFeederTimer, SliderTimer sliderTimer) {
        this.brand = brand;
        this.playlistManager = playlistManager;
        this.hlsConfig = hlsConfig;
        this.segmentFeederTimer = segmentFeederTimer;
        this.sliderTimer = sliderTimer;
    }


    public String generateMasterPlaylist(String brand) {
        long maxRate = 128000L;
        long halfRate = maxRate / 2;

        return "#EXTM3U\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=" + maxRate + "\n" +
                "/api/stream/" + brand.toLowerCase() + "/stream.m3u8?bitrate=" + maxRate + "\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=" + halfRate + "\n" +
                "/api/stream/" + brand.toLowerCase() + "/stream.m3u8?bitrate=" + halfRate + "\n";
    }

    public String generatePlaylist(String brand, Long bitrate) {
        if (streamState.liveSegments.isEmpty()) {
            LOGGER.warnf("%s liveSegments is EMPTY, pendingQueue size: %d",
                    logPrefix(), streamState.pendingQueue.size());
            return getDefaultPlaylist();
        }

        final long targetBitrate = bitrate != null ? bitrate : 128000L;
        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-ALLOW-CACHE:NO\n")
                .append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
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
                        String meta = segment.getSongMetadata() != null
                                ? segment.getSongMetadata().getTitle() + " - " + segment.getSongMetadata().getArtist()
                                : "";
                        playlist.append("#EXTINF:")
                                .append(segment.getDuration())
                                .append(",")
                                .append(meta)
                                .append("\n")
                                .append("/stream/")
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
            segmentMatcher.reset(segmentFile);
            if (!segmentMatcher.find()) {
                LOGGER.warnf("%s Segment '%s' doesn't match pattern: %s",
                        logPrefix(), segmentFile, SEGMENT_PATTERN.pattern());
                return null;
            }

            String brandFromSegment = segmentMatcher.group(1);
            if (!brandFromSegment.equalsIgnoreCase(brand)) {
                LOGGER.warnf("%s Segment brand '%s' doesn't match requested brand '%s'",
                        logPrefix(), brandFromSegment, brand);
                return null;
            }

            long bitrate = Long.parseLong(segmentMatcher.group(2));
            long sequence = Long.parseLong(segmentMatcher.group(3));

            Map<Long, HlsSegment> bitrateSlot = streamState.liveSegments.get(sequence);
            if (bitrateSlot == null) {
                LOGGER.debugf("%s Segment sequence %d not found in liveSegments", logPrefix(), sequence);
                return null;
            }

            HlsSegment segment = bitrateSlot.get(bitrate);
            if (segment == null) {
                LOGGER.debugf("%s Bitrate %d not found for sequence %d", logPrefix(), bitrate, sequence);
                if (!bitrateSlot.isEmpty()) {
                    segment = bitrateSlot.values().iterator().next();
                }
            }

            return segment;
        } catch (Exception e) {
            LOGGER.warnf(e, "%s Error processing segment request '%s'", logPrefix(), segmentFile);
            return null;
        }
    }

    private void feedSegments() {
        int pendingSize = streamState.pendingQueue.size();
        int liveSize = streamState.liveSegments.size();
        int maxVisible = hlsConfig.getMaxVisibleSegments() * 2;
        
        LOGGER.debugf("%s feedSegments: pending=%d, live=%d, max=%d", 
                logPrefix(), pendingSize, liveSize, maxVisible);
        
        if (!streamState.pendingQueue.isEmpty() && streamState.liveSegments.size() < maxVisible) {
            Map<Long, HlsSegment> bitrateSlot = streamState.pendingQueue.poll();
            if (bitrateSlot != null) {
                long seq = bitrateSlot.values().iterator().next().getSequence();
                streamState.liveSegments.put(seq, bitrateSlot);
            }
        }

        if (streamState.pendingQueue.size() < PENDING_QUEUE_REFILL_THRESHOLD) {
            LOGGER.infof("%s Pending queue below threshold (%d), fetching",
                    logPrefix(), streamState.pendingQueue.size());
            try {
                LiveSoundFragment fragment = playlistManager.getNextLiveFragment();
                if (fragment != null) {
                    addFragmentToPendingQueue(fragment);
                } else {
                    LOGGER.warnf("%s PlaylistManager returned null fragment", logPrefix());
                }
            } catch (Exception e) {
                LOGGER.errorf(e, "%s Error feeding segments", logPrefix());
            }
        }
    }

    private void addFragmentToPendingQueue(LiveSoundFragment fragment) {
        Map<Long, ConcurrentLinkedQueue<HlsSegment>> segments = fragment.getSegments();
        if (segments == null || segments.isEmpty()) {
            LOGGER.warnf("%s Fragment has no segments: %s",
                    logPrefix(), fragment.getSoundFragmentId());
            return;
        }

        ConcurrentLinkedQueue<HlsSegment> firstBitrateQueue = segments.values().iterator().next();
        int segmentCount = firstBitrateQueue.size();

        LOGGER.infof("%s Added pending frag with %d segments per bitrate",
                logPrefix(), segmentCount);

        for (int i = 0; i < segmentCount; i++) {
            long globalSeq = currentSequence.getAndIncrement();
            Map<Long, HlsSegment> bitrateSlot = new HashMap<>();

            for (Map.Entry<Long, ConcurrentLinkedQueue<HlsSegment>> entry : segments.entrySet()) {
                Long bitrate = entry.getKey();
                ConcurrentLinkedQueue<HlsSegment> queue = entry.getValue();

                HlsSegment segment = queue.poll();
                if (segment != null) {
                    segment.setSequence(globalSeq);
                    bitrateSlot.put(bitrate, segment);
                }
            }

            if (!bitrateSlot.isEmpty()) {
                streamState.pendingQueue.offer(bitrateSlot);
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

    public void initialize() {
        LOGGER.infof("%s Initializing stream", logPrefix());

        segmentFeederTimer.setDurationSec(hlsConfig.getSegmentDuration());

        feederSubscription = segmentFeederTimer.getTicker().subscribe().with(
                timestamp -> executorService.submit(this::feedSegments),
                error -> LOGGER.errorf(error, "%s Feeder subscription error", logPrefix())
        );

        sliderSubscription = sliderTimer.getTicker().subscribe().with(
                timestamp -> executorService.submit(this::slideWindow),
                error -> LOGGER.errorf(error, "%s Slider subscription error", logPrefix())
        );
    }

    public void shutdown() {
        LOGGER.infof("%s Shutting down stream", logPrefix());

        if (feederSubscription != null) {
            feederSubscription.cancel();
        }
        if (sliderSubscription != null) {
            sliderSubscription.cancel();
        }

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.errorf("%s Executor service did not terminate", logPrefix());
                }
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
            LOGGER.warnf("%s Shutdown interrupted", logPrefix());
        }

        streamState.liveSegments.clear();
        streamState.pendingQueue.clear();
        LOGGER.infof("%s Stream shutdown complete", logPrefix());
    }

    private String getDefaultPlaylist() {
        return "#EXTM3U\n" +
                "#EXT-X-VERSION:3\n" +
                "#EXT-X-ALLOW-CACHE:NO\n" +
                "#EXT-X-TARGETDURATION:" + hlsConfig.getSegmentDuration() + "\n" +
                "#EXT-X-MEDIA-SEQUENCE:0\n";
    }

    private String logPrefix() {
        return "[" + brand + "]";
    }
}