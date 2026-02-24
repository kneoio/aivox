package com.semantyca.aivox.model;

import lombok.Data;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
public class LiveRadioStationDTO {
    private String name;
    private String slugName;
    private String streamStatus;
    private String streamType;        // RADIO or ONE_TIME_STREAM
    private String djName;
    private String languageTag;
    private TtsDTO tts;
    private List<SongPromptDTO> prompts;
    private String info;
}
