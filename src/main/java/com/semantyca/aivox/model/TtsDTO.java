package com.semantyca.aivox.model;

import lombok.Data;

@Data
public class TtsDTO {
    private String primaryVoice;      // Voice ID for TTS
    private String secondaryVoice;    // For dialogue mode
    private String secondaryVoiceName;
    private String ttsEngineType;     // ELEVENLABS, AZURE
}
