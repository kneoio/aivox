package com.semantyca.aivox.streaming;

import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import lombok.Data;
import java.util.UUID;

@Data
public class SongMetadata {
    private UUID songId;
    private String title;
    private String artist;
    private String album;
    private String genre;
    private int duration;  // seconds
    private String languageCode;
    private PlaylistItemType itemType;
    
    public SongMetadata() {}
    
    public SongMetadata(UUID songId, String title, String artist) {
        this.songId = songId;
        this.title = title;
        this.artist = artist;
    }
}
