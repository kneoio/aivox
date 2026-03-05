package com.semantyca.aivox.service.live;

import com.semantyca.aivox.dto.BrandSoundFragmentDTO;
import com.semantyca.aivox.dto.aihelper.llmtool.AvailableStationsAiDTO;
import com.semantyca.aivox.dto.aihelper.llmtool.BrandSoundFragmentAiDTO;
import com.semantyca.aivox.dto.aihelper.llmtool.RadioStationAiDTO;
import com.semantyca.aivox.dto.dashboard.AiDjStatsDTO;
import com.semantyca.aivox.dto.radiostation.AiOverridingDTO;
import com.semantyca.aivox.dto.radiostation.BrandDTO;
import com.semantyca.aivox.service.AiAgentService;
import com.semantyca.aivox.service.BrandService;
import com.semantyca.aivox.service.soundfragment.SoundFragmentService;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.model.aiagent.AiAgent;
import com.semantyca.mixpla.model.aiagent.LanguagePreference;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.kneo.officeframe.service.GenreService;
import io.kneo.officeframe.service.LabelService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiHelperService.class);

    public record DjRequestInfo(LocalDateTime requestTime, String djName) {
    }

    private final Map<String, DjRequestInfo> aiDjStatsRequestTracker = new ConcurrentHashMap<>();
    private final Map<String, List<AiDjStatsDTO.StatusMessage>> aiDjMessagesTracker = new ConcurrentHashMap<>();

    private final SoundFragmentService soundFragmentService;
    private final GenreService genreService;
    private final LabelService labelService;
    private final BrandService brandService;
    private final AiAgentService aiAgentService;

    private static final int SCENE_START_SHIFT_MINUTES = 10;

    @Inject
    public AiHelperService(
            SoundFragmentService soundFragmentService,
            GenreService genreService,
            LabelService labelService, BrandService brandService, AiAgentService aiAgentService
    ) {
        this.soundFragmentService = soundFragmentService;
        this.genreService = genreService;
        this.labelService = labelService;
        this.brandService = brandService;
        this.aiAgentService = aiAgentService;
    }



    public Uni<List<BrandSoundFragmentAiDTO>> searchBrandSoundFragmentsForAi(
            String brandName,
            String keyword,
            Integer limit,
            Integer offset
    ) {
        int actualLimit = (limit != null && limit > 0) ? limit : 50;
        int actualOffset = (offset != null && offset >= 0) ? offset : 0;

        return soundFragmentService.getBrandSoundFragmentsBySimilarity(brandName, keyword, actualLimit, actualOffset)
                .chain(brandFragments -> {
                    if (brandFragments == null || brandFragments.isEmpty()) {
                        return Uni.createFrom().item(Collections.<BrandSoundFragmentAiDTO>emptyList());
                    }

                    List<Uni<BrandSoundFragmentAiDTO>> aiDtoUnis = brandFragments.stream()
                            .map(this::mapToBrandSoundFragmentAiDTO)
                            .collect(Collectors.toList());

                    return Uni.join().all(aiDtoUnis).andFailFast();
                });
    }

    public Uni<AvailableStationsAiDTO> getAllStations(List<StreamStatus> statuses, String country, LanguageTag djLanguage, String query) {
        return brandService.getAllDTO(1000, 0, SuperUser.build(), country, query)
                .flatMap(stations -> {
                    if (stations == null || stations.isEmpty()) {
                        AvailableStationsAiDTO container = new AvailableStationsAiDTO();
                        container.setRadioStations(List.of());
                        return Uni.createFrom().item(container);
                    }

                    List<Uni<RadioStationAiDTO>> unis = stations.stream()
                            .map(dto -> {
                                if (statuses != null && !statuses.contains(dto.getStatus())) {
                                    return Uni.createFrom().<RadioStationAiDTO>nullItem();
                                }

                                if (djLanguage != null) {
                                    if (dto.getAiAgentId() == null) {
                                        return Uni.createFrom().<RadioStationAiDTO>nullItem();
                                    }
                                    return aiAgentService.getById(dto.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                                            .map(agent -> {
                                                boolean supports = agent.getPreferredLang().stream()
                                                        .anyMatch(p -> p.getLanguageTag() == djLanguage);
                                                if (!supports) {
                                                    return null;
                                                }
                                                return toRadioStationAiDTO(dto, agent);
                                            })
                                            .onFailure().recoverWithItem(() -> null);
                                } else {
                                    if (dto.getAiAgentId() == null) {
                                        return Uni.createFrom().item(toRadioStationAiDTO(dto, null));
                                    }
                                    return aiAgentService.getById(dto.getAiAgentId(), SuperUser.build(), LanguageCode.en)
                                            .map(agent -> toRadioStationAiDTO(dto, agent))
                                            .onFailure().recoverWithItem(() -> toRadioStationAiDTO(dto, null));
                                }
                            })
                            .collect(Collectors.toList());

                    return (unis.isEmpty() ? Uni.createFrom().item(List.<RadioStationAiDTO>of()) : Uni.join().all(unis).andFailFast())
                            .map(list -> {
                                List<RadioStationAiDTO> stationsList = new ArrayList<>(list);
                                AvailableStationsAiDTO container = new AvailableStationsAiDTO();
                                container.setRadioStations(stationsList);
                                return container;
                            });
                });
    }


    private Uni<BrandSoundFragmentAiDTO> mapToBrandSoundFragmentAiDTO(BrandSoundFragmentDTO brandFragment) {
        BrandSoundFragmentAiDTO aiDto = new BrandSoundFragmentAiDTO();
        aiDto.setId(brandFragment.getSoundFragmentDTO().getId());
        aiDto.setTitle(brandFragment.getSoundFragmentDTO().getTitle());
        aiDto.setArtist(brandFragment.getSoundFragmentDTO().getArtist());
        aiDto.setAlbum(brandFragment.getSoundFragmentDTO().getAlbum());
        aiDto.setDescription(brandFragment.getSoundFragmentDTO().getDescription());
        aiDto.setPlayedByBrandCount(brandFragment.getPlayedByBrandCount());
        aiDto.setLastTimePlayedByBrand(brandFragment.getLastTimePlayedByBrand());

        List<UUID> genreIds = brandFragment.getSoundFragmentDTO().getGenres();
        List<UUID> labelIds = brandFragment.getSoundFragmentDTO().getLabels();

        Uni<List<String>> genresUni = (genreIds != null && !genreIds.isEmpty())
                ? Uni.join().all(genreIds.stream()
                .map(genreId -> genreService.getById(genreId)
                        .map(genre -> genre.getLocalizedName().getOrDefault(LanguageCode.en, "Unknown"))
                        .onFailure().recoverWithItem("Unknown"))
                .collect(Collectors.toList())).andFailFast()
                : Uni.createFrom().item(Collections.<String>emptyList());

        Uni<List<String>> labelsUni = (labelIds != null && !labelIds.isEmpty())
                ? Uni.join().all(labelIds.stream()
                .map(labelId -> labelService.getById(labelId)
                        .map(label -> label.getLocalizedName().getOrDefault(LanguageCode.en, "Unknown"))
                        .onFailure().recoverWithItem("Unknown"))
                .collect(Collectors.toList())).andFailFast()
                : Uni.createFrom().item(Collections.<String>emptyList());

        return Uni.combine().all().unis(genresUni, labelsUni).asTuple()
                .map(tuple -> {
                    aiDto.setGenres(tuple.getItem1());
                    aiDto.setLabels(tuple.getItem2());
                    return aiDto;
                });
    }

    private RadioStationAiDTO toRadioStationAiDTO(BrandDTO brandDTO, AiAgent agent) {
        RadioStationAiDTO b = new RadioStationAiDTO();
        b.setLocalizedName(brandDTO.getLocalizedName());
        b.setSlugName(brandDTO.getSlugName());
        b.setCountry(brandDTO.getCountry());
        b.setHlsUrl(brandDTO.getHlsUrl());
        b.setMp3Url(brandDTO.getMp3Url());
        b.setMixplaUrl(brandDTO.getMixplaUrl());
        b.setTimeZone(brandDTO.getTimeZone());
        b.setDescription(brandDTO.getDescription());
        b.setBitRate(brandDTO.getBitRate());
        b.setStreamStatus(brandDTO.getStatus());
        if (agent != null) {
            b.setDjName(agent.getName());
            List<LanguageTag> langs = agent.getPreferredLang().stream()
                    .sorted(Comparator.comparingDouble(LanguagePreference::getWeight).reversed())
                    .map(LanguagePreference::getLanguageTag)
                    .collect(Collectors.toList());
            b.setAiAgentLang(langs);
        }

        if (brandDTO.isAiOverridingEnabled()){
            AiOverridingDTO aiOverriding = brandDTO.getAiOverriding();
            b.setOverriddenDjName(aiOverriding.getName());
            b.setAdditionalUserInstruction(aiOverriding.getPrompt());
        }
        return b;
    }

}
