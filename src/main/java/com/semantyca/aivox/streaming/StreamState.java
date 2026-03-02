package com.semantyca.aivox.streaming;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;

class StreamState {
    final ConcurrentSkipListMap<Long, Map<Long, HlsSegment>> liveSegments = new ConcurrentSkipListMap<>();
    final Queue<Map<Long, HlsSegment>> pendingQueue = new ConcurrentLinkedQueue<>();
}
