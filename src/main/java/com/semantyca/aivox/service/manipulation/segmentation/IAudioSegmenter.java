package com.semantyca.aivox.service.manipulation.segmentation;


import com.semantyca.aivox.model.SegmentInfo;
import com.semantyca.aivox.streaming.HlsSegment;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public interface IAudioSegmenter {

    ConcurrentLinkedQueue<HlsSegment> slice(SoundFragment soundFragment);

    List<SegmentInfo> segmentAudioFile(Path audioFilePath, String songMetadata, UUID fragmentId);

}