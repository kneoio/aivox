package com.semantyca.aivox.model;


import com.semantyca.aivox.streaming.SongMetadata;

public record SegmentInfo(String path, SongMetadata songMetadata, int duration, int sequenceIndex) {

}
