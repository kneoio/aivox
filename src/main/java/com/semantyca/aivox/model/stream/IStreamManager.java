package com.semantyca.aivox.model.stream;

import com.semantyca.aivox.model.IStream;
import com.semantyca.aivox.service.manipulation.AudioSegmentationService;
import com.semantyca.aivox.service.playlist.PlaylistManager;
import com.semantyca.aivox.service.soundfragment.SoundFragmentService;
import com.semantyca.aivox.service.stream.StreamManagerStats;
import com.semantyca.aivox.streaming.HlsSegment;

public interface IStreamManager {

    void initialize(IStream stream);

    String generatePlaylist(String clientId);

    String generateMasterPlaylist();

    HlsSegment getSegment(String segmentParam);

    HlsSegment getSegment(long sequence);

    void shutdown();

    IStream getStream();

    SoundFragmentService getSoundFragmentService();

    AudioSegmentationService getSegmentationService();

    StreamManagerStats getStats();

    PlaylistManager getPlaylistManager();

}