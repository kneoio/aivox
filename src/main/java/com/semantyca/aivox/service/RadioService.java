package com.semantyca.aivox.service;

import com.semantyca.aivox.dto.player.StatusMixplaDTO;
import com.semantyca.aivox.streaming.RadioStationPool;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.cnst.AiAgentStatus;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import com.semantyca.mixpla.model.stream.IStream;
import com.semantyca.mixpla.service.exceptions.RadioStationException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RadioService {
    @Inject
    RadioStationPool radioStationPool;


    public Uni<StatusMixplaDTO> getStatus(String brand) {
        return radioStationPool.get(brand)
                .onItem().ifNull().failWith(() ->
                        new RadioStationException(RadioStationException.ErrorType.STATION_NOT_ACTIVE))
                .chain(s -> toRadioStatusDTO(s, null));
    }

    public Uni<StatusMixplaDTO> toRadioStatusDTO(IStream stream, Brand brand) {
        if (stream == null) {
            return Uni.createFrom().nullItem();
        }
        return buildRadioStatusDTO(
                stream.getLocalizedName().getOrDefault(
                        stream.getCountry().getPreferredLanguage(), stream.getSlugName()),
                stream.getSlugName(),
                stream.getManagedBy().toString(),
                stream.getAiAgentStatus(),
                stream.getStatus(),
                stream.getCountry().name(),
                stream.getColor());
    }

    private Uni<StatusMixplaDTO> buildRadioStatusDTO(
            String stationName,
            String slugName,
            String managedByType,
            AiAgentStatus agentStatus,
            StreamStatus stationStatus,
            String stationCountryCode,
            String color
    ) {
        String currentStatus = stationStatus != null
                ? stationStatus.name()
                : StreamStatus.OFF_LINE.name();

        String resolvedAgentStatus = agentStatus != null
                ? agentStatus.name()
                : AiAgentStatus.UNDEFINED.name();


        return Uni.createFrom().item(
                        new StatusMixplaDTO(
                                stationName,
                                slugName,
                                managedByType,
                                null,
                                resolvedAgentStatus,
                                currentStatus,
                                stationCountryCode,
                                color,
                                "Drexs",
                                null
                        )
                );
    }

    public Uni<IStream> initializeStation(String brand) {
        return null;
    }

    public Uni<IStream> stopStation(String brand) {
        return null;
    }
}

