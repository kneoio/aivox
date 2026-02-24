package com.semantyca.aivox.streaming;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
public class WaitingAudioProvider {
    
    private static final Logger LOGGER = Logger.getLogger(WaitingAudioProvider.class);
    private static final String WAITING_AUDIO_PATH = "src/main/resources/audio/Waiting_State.wav";
    
    private byte[] waitingAudioData;
    private UUID waitingSongId;
    
    public void initialize() {
        try {
            Path waitingFile = Paths.get(WAITING_AUDIO_PATH);
            if (Files.exists(waitingFile)) {
                waitingAudioData = Files.readAllBytes(waitingFile);
                waitingSongId = UUID.randomUUID();
                LOGGER.info("Loaded waiting audio file: " + WAITING_AUDIO_PATH + " (" + waitingAudioData.length + " bytes)");
            } else {
                LOGGER.warn("Waiting audio file not found: " + WAITING_AUDIO_PATH);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load waiting audio file: " + e.getMessage(), e);
        }
    }
    
    public Uni<LiveSoundFragment> createWaitingFragment() {
        if (waitingAudioData == null) {
            LOGGER.warn("Waiting audio not available, returning null");
            return Uni.createFrom().nullItem();
        }
        
        return Uni.createFrom().item(() -> {
            LiveSoundFragment fragment = new LiveSoundFragment();
            fragment.setSoundFragmentId(waitingSongId);
            fragment.setMetadata(new SongMetadata(waitingSongId, "Waiting State", "System"));
            
            // Create segments for different bitrates
            Map<Long, ConcurrentLinkedQueue<HlsSegment>> segments = new HashMap<>();
            long[] bitrates = {128000L, 64000L};
            
            for (long bitrate : bitrates) {
                ConcurrentLinkedQueue<HlsSegment> segmentQueue = new ConcurrentLinkedQueue<>();
                
                // Create a single segment with the waiting audio
                HlsSegment segment = new HlsSegment();
                segment.setSequence(0);
                segment.setDuration(6); // 6 seconds
                segment.setData(waitingAudioData);
                segment.setSongMetadata(fragment.getMetadata());
                segmentQueue.offer(segment);
                
                segments.put(bitrate, segmentQueue);
            }
            
            fragment.setSegments(segments);
            LOGGER.debug("Created waiting fragment with song ID: " + waitingSongId);
            return fragment;
        });
    }
    
    public boolean isWaitingAudioAvailable() {
        return waitingAudioData != null;
    }
}
