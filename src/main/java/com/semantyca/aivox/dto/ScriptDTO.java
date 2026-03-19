package com.semantyca.aivox.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.core.dto.AbstractDTO;
import com.semantyca.core.model.ScriptVariable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScriptDTO extends AbstractDTO {
    private String name;
    private String slugName;
    private UUID defaultProfileId;
    private String description;
    private Integer accessLevel = 0;
    private String languageTag;
    private String timingMode;
    private List<UUID> labels;
    private List<UUID> brands;
    private List<SceneDTO> scenes;
    private List<ScriptVariable> requiredVariables;
}
