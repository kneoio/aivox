package com.semantyca.aivox.service.playlist;

import com.semantyca.aivox.model.soundfragment.SoundFragment;
import lombok.Getter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Getter
public class SupplierSongMemory {
    private final Set<SoundFragment> playedSongs = new HashSet<>();

    public void updateLastSelected(List<SoundFragment> selectedFragments) {
        playedSongs.addAll(selectedFragments);
    }

    public boolean wasPlayed(SoundFragment song) {
        return playedSongs.contains(song);
    }

    public void reset() {
        playedSongs.clear();
    }

}