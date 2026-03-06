package com.semantyca.aivox.dto.dashboard;

import com.semantyca.mixpla.model.cnst.LiveSongSource;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LiveSoundFragmentDTO {
    private int duration;
    private String title;
    private String artist;
    private PlaylistItemType itemType;
    private LiveSongSource queueType;
}