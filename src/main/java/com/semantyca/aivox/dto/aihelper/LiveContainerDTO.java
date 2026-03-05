package com.semantyca.aivox.dto.aihelper;

import com.semantyca.aivox.dto.LiveRadioStationDTO;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class LiveContainerDTO {
    private List<LiveRadioStationDTO> radioStations;
}