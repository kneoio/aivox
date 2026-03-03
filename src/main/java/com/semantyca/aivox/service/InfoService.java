package com.semantyca.aivox.service;

import com.semantyca.aivox.dto.RadioStationStatusDTO;
import com.semantyca.aivox.model.IStream;
import com.semantyca.aivox.model.aiagent.AiAgent;
import com.semantyca.aivox.model.aiagent.LanguagePreference;
import com.semantyca.aivox.model.brand.AiOverriding;
import com.semantyca.aivox.model.brand.Brand;
import com.semantyca.aivox.streaming.RadioStationPool;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.model.cnst.AiAgentStatus;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class InfoService {
    private final RadioStationPool radioStationPool;
    private final BrandService brandService;
    private final AiAgentService aiAgentService;
    private final Random random = new Random();

    @Inject
    public InfoService(RadioStationPool radioStationPool, BrandService brandService, AiAgentService aiAgentService) {
        this.radioStationPool = radioStationPool;
        this.brandService = brandService;
        this.aiAgentService = aiAgentService;
    }

    public Uni<List<RadioStationStatusDTO>> getAllStations(Boolean onlineOnly) {
        return Uni.combine().all().unis(getOnlineStations(), brandService.getAll(1000, 0))
                .asTuple().chain(t -> {
                    List<IStream> online = t.getItem1();
                    List<Brand> all = t.getItem2();

                    List<Uni<RadioStationStatusDTO>> unis = all.stream()
                            .filter(b -> onlineOnly == null || !onlineOnly ||
                                    online.stream().anyMatch(o -> o.getSlugName().equals(b.getSlugName())))
                            .map(b -> {
                                IStream os = online.stream()
                                        .filter(o -> o.getSlugName().equals(b.getSlugName()))
                                        .findFirst().orElse(null);
                                return os != null
                                        ? toStatusDTO(os, b)
                                        : brandToStatusDTO(b);
                            })
                            .toList();

                    return unis.isEmpty()
                            ? Uni.createFrom().item(List.of())
                            : Uni.join().all(unis).andFailFast();
                });
    }


    public Uni<RadioStationStatusDTO> toStatusDTO(IStream stream, Brand brand) {
        if (stream == null) return Uni.createFrom().nullItem();
        return buildStatusDTO(
                stream.getLocalizedName().getOrDefault(
                        stream.getCountry().getPreferredLanguage(), stream.getSlugName()),
                stream.getSlugName(),
                stream.getManagedBy().toString(),
                stream.getAiAgentId(),
                stream.getAiOverriding(),
                stream.getAiAgentStatus(),
                stream.getStatus(),
                stream.getCountry().name(),
                stream.getColor(),
                stream.getDescription(),
                brand != null ? brand.getBitRate() : 0,
                brand != null ? brand.getPopularityRate() : 0.0);
    }

    public Uni<RadioStationStatusDTO> brandToStatusDTO(Brand brand) {
        if (brand == null) return Uni.createFrom().nullItem();
        return buildStatusDTO(
                brand.getLocalizedName().getOrDefault(
                        brand.getCountry().getPreferredLanguage(), brand.getSlugName()),
                brand.getSlugName(),
                brand.getManagedBy().toString(),
                brand.getAiAgentId(),
                brand.getAiOverriding(),
                brand.getAiAgentStatus(),
                brand.getStatus(),
                brand.getCountry().name(),
                brand.getColor(),
                brand.getDescription(),
                brand.getBitRate(),
                brand.getPopularityRate());
    }


    private Uni<RadioStationStatusDTO> buildStatusDTO(
            String stationName,
            String slugName,
            String managedByType,
            UUID aiAgentId,
            AiOverriding overriddenAiDj,
            AiAgentStatus agentStatus,
            StreamStatus stationStatus,
            String stationCountryCode,
            String color,
            String description,
            long bitRate,
            double popularityRate
    ) {
        String currentStatus = stationStatus != null
                ? stationStatus.name()
                : StreamStatus.OFF_LINE.name();

        String resolvedAgentStatus = agentStatus != null
                ? agentStatus.name()
                : AiAgentStatus.UNDEFINED.name();

        if (aiAgentId == null) {
            RadioStationStatusDTO dto = new RadioStationStatusDTO(
                    stationName,
                    slugName,
                    managedByType,
                    null,
                    null,
                    resolvedAgentStatus,
                    currentStatus,
                    stationCountryCode,
                    color,
                    description,
                    0,
                    bitRate,
                    popularityRate
            );
            return Uni.createFrom().item(dto);
        }

        return aiAgentService
                .getById(aiAgentId, SuperUser.build(), LanguageCode.en)
                .onItem().transform(aiAgent -> {
                    LanguageTag selectedLang = selectLanguageByWeight(aiAgent);

                    String djName = (overriddenAiDj != null && overriddenAiDj.getName() != null)
                            ? overriddenAiDj.getName()
                            : aiAgent.getName();

                    return new RadioStationStatusDTO(
                            stationName,
                            slugName,
                            managedByType,
                            djName,
                            selectedLang.toLanguageCode().name(),
                            resolvedAgentStatus,
                            currentStatus,
                            stationCountryCode,
                            color,
                            description,
                            0,
                            bitRate,
                            popularityRate
                    );
                })
                .onFailure().recoverWithItem(() ->
                        new RadioStationStatusDTO(
                                stationName,
                                slugName,
                                managedByType,
                                null,
                                null,
                                resolvedAgentStatus,
                                currentStatus,
                                stationCountryCode,
                                color,
                                description,
                                0,
                                bitRate,
                                popularityRate
                        )
                );
    }


    private Uni<List<IStream>> getOnlineStations() {
        return Uni.createFrom().item(
                new ArrayList<>(radioStationPool.getOnlineStationsSnapshot()));
    }

    @Deprecated
    private LanguageTag selectLanguageByWeight(AiAgent agent) {

        List<LanguagePreference> p = agent.getPreferredLang();
        if (p == null || p.isEmpty()) return LanguageTag.EN_US;
        if (p.size() == 1) return p.getFirst().getLanguageTag();

        double total = p.stream().mapToDouble(LanguagePreference::getWeight).sum();
        double r = random.nextDouble() * total;
        double c = 0;
        for (LanguagePreference lp : p) {
            c += lp.getWeight();
            if (r <= c) return lp.getLanguageTag();
        }
        return p.getFirst().getLanguageTag();
    }
}
