package com.semantyca.aivox.streaming;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
public class WaitingAudioProvider {
    
    private static final Logger LOGGER = Logger.getLogger(WaitingAudioProvider.class);
    private static final String WAITING_AUDIO_PATH = "src/main/resources/audio/Waiting_State.wav";
    private static final int SEGMENT_DURATION = 6;
    private static final int CHUNK_SIZE = 32768;
    
    private byte[] waitingAudioData;
    private UUID waitingSongId;
    private Map<Long, List<HlsSegment>> originalWaitingSegments = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    
    public void initialize() {
        if (initialized) {
            return;
        }
        
        try {
            Path waitingFile = Paths.get(WAITING_AUDIO_PATH);
            if (Files.exists(waitingFile)) {
                waitingAudioData = Files.readAllBytes(waitingFile);
                waitingSongId = UUID.randomUUID();
                
                createWaitingSegments();
                
                initialized = true;
                LOGGER.info("Loaded and segmented waiting audio file: " + WAITING_AUDIO_PATH + " (" + waitingAudioData.length + " bytes, " + originalWaitingSegments.size() + " bitrate variants)");
            } else {
                LOGGER.warn("Waiting audio file not found: " + WAITING_AUDIO_PATH);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load waiting audio file: " + e.getMessage(), e);
        }
    }
    
    private void createWaitingSegments() {
        long[] bitrates = {128000L, 64000L};
        
        for (long bitrate : bitrates) {
            List<HlsSegment> segmentList = new ArrayList<>();
            
            int totalSegments = Math.max(1, waitingAudioData.length / CHUNK_SIZE);
            
            for (int i = 0; i < totalSegments; i++) {
                int start = i * CHUNK_SIZE;
                int end = Math.min(start + CHUNK_SIZE, waitingAudioData.length);
                byte[] segmentData = Arrays.copyOfRange(waitingAudioData, start, end);
                
                HlsSegment segment = new HlsSegment();
                segment.setSequence(i);
                segment.setDuration(SEGMENT_DURATION);
                segment.setData(segmentData);
                segment.setBitrate(bitrate);
                segment.setSongMetadata(new SongMetadata(waitingSongId, "Waiting...", "Station"));
                segment.setFirstSegmentOfFragment(i == 0);
                
                segmentList.add(segment);
            }
            
            originalWaitingSegments.put(bitrate, segmentList);
            LOGGER.debug("Created " + segmentList.size() + " segments for bitrate " + bitrate);
        }
    }
    
    public Uni<LiveSoundFragment> createWaitingFragment() {
        if (!initialized || originalWaitingSegments.isEmpty()) {
            LOGGER.warn("Waiting audio not initialized, returning null");
            return Uni.createFrom().nullItem();
        }
        
        return Uni.createFrom().item(() -> {
            LiveSoundFragment fragment = new LiveSoundFragment();
            fragment.setSoundFragmentId(waitingSongId);
            fragment.setMetadata(new SongMetadata(waitingSongId, "Waiting...", "Station"));
            fragment.setPriority(999);
            
            Map<Long, ConcurrentLinkedQueue<HlsSegment>> clonedSegments = new ConcurrentHashMap<>();
            
            for (Map.Entry<Long, List<HlsSegment>> entry : originalWaitingSegments.entrySet()) {
                ConcurrentLinkedQueue<HlsSegment> queue = new ConcurrentLinkedQueue<>();
                
                for (HlsSegment originalSegment : entry.getValue()) {
                    HlsSegment clonedSegment = new HlsSegment();
                    clonedSegment.setSequence(originalSegment.getSequence());
                    clonedSegment.setDuration(originalSegment.getDuration());
                    clonedSegment.setData(originalSegment.getData());
                    clonedSegment.setBitrate(originalSegment.getBitrate());
                    clonedSegment.setSongMetadata(fragment.getMetadata());
                    clonedSegment.setFirstSegmentOfFragment(originalSegment.isFirstSegmentOfFragment());
                    
                    queue.offer(clonedSegment);
                }
                
                clonedSegments.put(entry.getKey(), queue);
            }
            
            fragment.setSegments(clonedSegments);
            LOGGER.debug("Created waiting fragment loop with " + clonedSegments.size() + " bitrate variants");
            return fragment;
        });
    }
    
    public boolean isWaitingAudioAvailable() {
        return initialized && !originalWaitingSegments.isEmpty();
    }
    
    public Map<Long, List<HlsSegment>> getOriginalSegments() {
        return new HashMap<>(originalWaitingSegments);
    }
}
