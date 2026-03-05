package com.semantyca.aivox.service.stream;

import com.semantyca.aivox.service.stats.HLSSongStats;
import com.semantyca.aivox.streaming.HlsSegment;

import java.util.Map;

public record StreamManagerStats(Map<Long, HlsSegment> liveSegments, boolean heartbeat) {

    public HLSSongStats getSongStatistics() {
        return liveSegments.values().stream()
                .map(segment -> new HLSSongStats(
                        segment.getSongMetadata()
                ))
                .findFirst()
                .orElse(null);
    }
}
