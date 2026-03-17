package com.semantyca.aivox.service;

import com.semantyca.aivox.dto.radiostation.AiOverridingDTO;
import com.semantyca.aivox.dto.radiostation.BrandDTO;
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
        assert repository != null;
        return repository.getAll(limit, offset);
    }

    public Uni<List<Brand>> getAll(final int limit, final int offset, IUser user) {
        assert repository != null;
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

    public Uni<List<BrandDTO>> getAllDTO(final int limit, final int offset) {
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
        assert radiostationPool != null;
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier()),
                radiostationPool.getLiveStatus(doc.getSlugName())
        ).asTuple().map(tuple -> {
            BrandDTO dto = new BrandDTO();
            dto.setId(doc.getId());
            dto.setLocalizedName(doc.getLocalizedName());
            dto.setCountry(doc.getCountry() != null ? doc.getCountry().name() : null);
            dto.setColor(doc.getColor());
            dto.setTimeZone(doc.getTimeZone().getId());
            dto.setDescription(doc.getDescription());
            dto.setTitleFont(doc.getTitleFont());
            dto.setSlugName(doc.getSlugName());
            dto.setManagedBy(doc.getManagedBy());
            dto.setOneTimeStreamPolicy(doc.getOneTimeStreamPolicy());
            dto.setSubmissionPolicy(doc.getSubmissionPolicy());
            dto.setMessagingPolicy(doc.getMessagingPolicy());
            dto.setIsTemporary(doc.getIsTemporary());
            dto.setPopularityRate(doc.getPopularityRate());

            if (doc.getAiOverriding() != null) {
                AiOverridingDTO aiDto = new AiOverridingDTO();
                aiDto.setName(doc.getAiOverriding().getName());
                dto.setAiOverriding(aiDto);
                dto.setAiOverridingEnabled(true);
            } else {
                dto.setAiOverridingEnabled(false);
            }

            StreamStatus liveStatus = tuple.getItem3().getStatus();
            dto.setStatus(liveStatus);


            return dto;
        });
    }
}