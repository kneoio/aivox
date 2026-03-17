package com.semantyca.aivox.dto.radiostation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.semantyca.core.dto.AbstractDTO;
import com.semantyca.core.dto.validation.ValidCountry;
import com.semantyca.core.dto.validation.ValidLocalizedName;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.mixpla.model.cnst.ManagedBy;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import com.semantyca.mixpla.model.cnst.SubmissionPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.URL;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BrandDTO extends AbstractDTO {
    private EnumMap<LanguageCode, String> localizedName = new EnumMap<>(LanguageCode.class);
    private String slugName;
    private String country;
    private ManagedBy managedBy;
    private URL hlsUrl;
    private URL iceCastUrl;
    private URL mp3Url;
    private URL mixplaUrl;
    private String timeZone;
    private String color;
    private String description;
    private String titleFont;
    private double popularityRate;
    private StreamStatus status = StreamStatus.OFF_LINE;
    private SubmissionPolicy oneTimeStreamPolicy = SubmissionPolicy.NOT_ALLOWED;
    private SubmissionPolicy submissionPolicy = SubmissionPolicy.NOT_ALLOWED;
    private SubmissionPolicy messagingPolicy = SubmissionPolicy.REVIEW_REQUIRED;
    private Integer isTemporary = 0;
    private boolean aiOverridingEnabled;
    private AiOverridingDTO aiOverriding;
}