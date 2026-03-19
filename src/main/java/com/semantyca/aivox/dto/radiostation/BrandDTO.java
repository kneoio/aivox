package com.semantyca.aivox.dto.radiostation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.EnumMap;

@Setter
@Getter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BrandDTO {
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private String slugName;
    private String djName;
    private String country;
    private String timeZone;
    private String color;
    private String description;
    private String titleFont;
    private double popularityRate;
    private StreamStatus status = StreamStatus.OFF_LINE;
    private Integer isTemporary = 0;
}