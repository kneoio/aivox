package com.semantyca.aivox.service;

import com.semantyca.aivox.dto.radiostation.AiOverridingDTO;
import com.semantyca.aivox.dto.radiostation.BrandDTO;
import com.semantyca.aivox.dto.radiostation.BrandScriptEntryDTO;
import com.semantyca.aivox.dto.radiostation.OwnerDTO;
import com.semantyca.aivox.dto.radiostation.ProfileOverridingDTO;
import com.semantyca.aivox.repository.brand.BrandRepository;
import com.semantyca.aivox.streaming.RadioStationPool;
import com.semantyca.core.model.cnst.LanguageCode;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.service.AbstractService;
import com.semantyca.core.service.UserService;
import com.semantyca.mixpla.model.brand.Brand;
import com.semantyca.mixpla.model.cnst.StreamStatus;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class BrandService extends AbstractService<Brand, BrandDTO> {
    private final BrandRepository repository;
    private final RadioStationPool radiostationPool;

    protected BrandService() {
        super();
        this.repository = null;
        this.radiostationPool = null;
    }

    @Inject
    public BrandService(UserService userService, BrandRepository repository, RadioStationPool radiostationPool) {
        super(userService);
        this.repository = repository;
        this.radiostationPool = radiostationPool;
    }

    public Uni<List<Brand>> getAll(final int limit, final int offset) {
        return repository.getAll(limit, offset);
    }

    public Uni<List<Brand>> getAll(final int limit, final int offset, IUser user) {
        return repository.getAll(limit, offset);
    }

    @Override
    public Uni<BrandDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        assert repository != null;
        return repository.findById(id, user, false).chain(this::mapToDTO);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        return null;
    }

    public Uni<Brand> getById(UUID id, IUser user) {
        assert repository != null;
        return repository.findById(id, user, true);
    }


    public Uni<Brand> getBySlugName(String name) {
        assert repository != null;
        return repository.getBySlugName(name);
    }

    public Uni<List<BrandDTO>> getAllDTO(final int limit, final int offset, final IUser user, final String country, final String query) {
        assert repository != null;
        return repository.getAll(limit, offset)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<BrandDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }


    private Uni<BrandDTO> mapToDTO(Brand doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier()),
                radiostationPool.getLiveStatus(doc.getSlugName()),
                repository.getScriptEntriesForBrand(doc.getId())
        ).asTuple().map(tuple -> {
            BrandDTO dto = new BrandDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setLocalizedName(doc.getLocalizedName());
            dto.setCountry(doc.getCountry() != null ? doc.getCountry().name() : null);
            dto.setColor(doc.getColor());
            dto.setTimeZone(doc.getTimeZone().getId());
            dto.setDescription(doc.getDescription());
            dto.setTitleFont(doc.getTitleFont());
            dto.setSlugName(doc.getSlugName());
            dto.setManagedBy(doc.getManagedBy());
            dto.setBitRate(doc.getBitRate());
            dto.setAiAgentId(doc.getAiAgentId());
            dto.setProfileId(doc.getProfileId());
            dto.setOneTimeStreamPolicy(doc.getOneTimeStreamPolicy());
            dto.setSubmissionPolicy(doc.getSubmissionPolicy());
            dto.setMessagingPolicy(doc.getMessagingPolicy());
            dto.setIsTemporary(doc.getIsTemporary());
            dto.setPopularityRate(doc.getPopularityRate());

            if (doc.getAiOverriding() != null) {
                AiOverridingDTO aiDto = new AiOverridingDTO();
                aiDto.setName(doc.getAiOverriding().getName());
                aiDto.setPrompt(doc.getAiOverriding().getPrompt());
                aiDto.setPrimaryVoice(doc.getAiOverriding().getPrimaryVoice());
                dto.setAiOverriding(aiDto);
                dto.setAiOverridingEnabled(true);
            } else {
                dto.setAiOverridingEnabled(false);
            }

            if (doc.getProfileOverriding() != null) {
                ProfileOverridingDTO profileDto = new ProfileOverridingDTO();
                profileDto.setName(doc.getProfileOverriding().getName());
                profileDto.setDescription(doc.getProfileOverriding().getDescription());
                dto.setProfileOverriding(profileDto);
                dto.setProfileOverridingEnabled(true);
            } else {
                dto.setProfileOverridingEnabled(false);
            }

            try {
                //dto.setHlsUrl(URI.create(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/radio/stream.m3u8").toURL());
                //dto.setIceCastUrl(URI.create(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/radio/icecast").toURL());
                //dto.setMp3Url(URI.create(broadcasterConfig.getHost() + "/" + dto.getSlugName() + "/radio/stream.mp3").toURL());
                dto.setMixplaUrl(URI.create("https://player.mixpla.io/?radio=" + dto.getSlugName()).toURL());
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
            dto.setArchived(doc.getArchived());
            List<BrandScriptEntryDTO> scriptDTOs = tuple.getItem4().stream()
                    .map(entry -> {
                        BrandScriptEntryDTO scriptDTO = new BrandScriptEntryDTO();
                        scriptDTO.setScriptId(entry.getScriptId());
                        scriptDTO.setUserVariables(entry.getUserVariables());
                        return scriptDTO;
                    })
                    .collect(Collectors.toList());
            dto.setScripts(scriptDTOs);
            StreamStatus liveStatus = tuple.getItem3().getStatus();
            dto.setStatus(liveStatus);

            if (doc.getOwner() != null) {
                OwnerDTO ownerDTO = new OwnerDTO();
                ownerDTO.setName(doc.getOwner().getName());
                ownerDTO.setEmail(doc.getOwner().getEmail());
                dto.setOwner(ownerDTO);
            }

            return dto;
        });
    }
}