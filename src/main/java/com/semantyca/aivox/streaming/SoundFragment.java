package com.semantyca.aivox.streaming;

import lombok.Data;

import java.util.UUID;

@Data
public class SoundFragment {
    private UUID id;
    private String title;
    private String artist;
    private byte[] audioData;
    private int duration;  // seconds
    private String streamUrl;
    
    public SoundFragment() {}
    
    public SoundFragment(UUID id, String title, String artist, byte[] audioData) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.audioData = audioData;
    }
}
