package com.semantyca.aivox.service.playlist;

import com.semantyca.aivox.streaming.LiveSoundFragment;

import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;

// Inner class to maintain brand-specific state
class PlaylistState {
    final LinkedList<LiveSoundFragment> obtainedByHlsPlaylist = new LinkedList<>();
    final ConcurrentLinkedQueue<LiveSoundFragment> regularQueue = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<LiveSoundFragment> prioritizedQueue = new ConcurrentLinkedQueue<>();
    final LinkedList<LiveSoundFragment> fragmentsForMp3 = new LinkedList<>();
}
