package com.semantyca.aivox.service.live;

import com.semantyca.aivox.dto.BrandSoundFragmentDTO;
import com.semantyca.aivox.dto.aihelper.llmtool.BrandSoundFragmentAiDTO;
import com.semantyca.aivox.dto.dashboard.AiDjStatsDTO;
import com.semantyca.aivox.service.soundfragment.SoundFragmentService;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.officeframe.service.GenreService;
import com.semantyca.officeframe.service.LabelService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class AiHelperService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiHelperService.class);
    private final Map<String, List<AiDjStatsDTO.StatusMessage>> aiDjMessagesTracker = new ConcurrentHashMap<>();
    private final SoundFragmentService soundFragmentService;
    private final GenreService genreService;
    private final LabelService labelService;

    @Inject
    public AiHelperService(
            SoundFragmentService soundFragmentService,
            GenreService genreService,
            LabelService labelService
    ) {
        this.soundFragmentService = soundFragmentService;
        this.genreService = genreService;
        this.labelService = labelService;
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

}
