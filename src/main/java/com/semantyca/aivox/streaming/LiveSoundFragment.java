package com.semantyca.aivox.streaming;

import lombok.Data;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Data
public class LiveSoundFragment {
    private UUID soundFragmentId;
    private Map<Long, ConcurrentLinkedQueue<HlsSegment>> segments;  // bitrate -> segments
    private int queueNum;
    private Integer priority;         // Lower = higher priority
    private SongMetadata metadata;
    
    public LiveSoundFragment() {
        this.segments = new ConcurrentHashMap<>();
    }
    
    public LiveSoundFragment(UUID soundFragmentId, int queueNum, Integer priority, SongMetadata metadata) {
        this.soundFragmentId = soundFragmentId;
        this.queueNum = queueNum;
        this.priority = priority;
        this.metadata = metadata;
        this.segments = new ConcurrentHashMap<>();
    }
}
