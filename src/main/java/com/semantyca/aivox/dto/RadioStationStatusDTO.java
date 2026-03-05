package com.semantyca.aivox.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RadioStationStatusDTO {
    private String name;
    private String slugName;
    private String managedBy;
    private String djName;
    private String djPreferredLang;
    private String countryCode;
    private String color;
    private String description;
    private long availableSongs;
    private long bitRate;
    private double popularityRate;
}
