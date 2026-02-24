package com.semantyca.aivox.streaming;

import lombok.Data;

@Data
public class HlsSegment {
    private long sequence;
    private byte[] data;              // TS file bytes
    private int duration;             // Seconds
    private long bitrate;
    private SongMetadata songMetadata;
    private boolean firstSegmentOfFragment;
    
    public HlsSegment() {}
    
    public HlsSegment(long sequence, byte[] data, int duration, long bitrate, 
                     SongMetadata songMetadata, boolean firstSegmentOfFragment) {
        this.sequence = sequence;
        this.data = data;
        this.duration = duration;
        this.bitrate = bitrate;
        this.songMetadata = songMetadata;
        this.firstSegmentOfFragment = firstSegmentOfFragment;
    }
}
