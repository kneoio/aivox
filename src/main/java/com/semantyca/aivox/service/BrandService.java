package com.semantyca.aivox.service;

import com.semantyca.aivox.model.brand.Brand;
import com.semantyca.aivox.repository.brand.BrandRepository;
import com.semantyca.aivox.streaming.RadioStationPool;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class BrandService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BrandService.class);

    private final BrandRepository repository;

    private final RadioStationPool radiostationPool;

    @Inject
    public BrandService(
            BrandRepository repository,
            RadioStationPool radiostationPool
    ) {
        this.repository = repository;
        this.radiostationPool = radiostationPool;
    }

    public Uni<List<Brand>> getAll(final int limit, final int offset) {
        return repository.getAll(limit, offset, false, SuperUser.build(), null, null);
    }

    public Uni<List<Brand>> getAll(final int limit, final int offset, IUser user) {
        return repository.getAll(limit, offset, false, user, null, null);
    }

    public Uni<Brand> getById(UUID id, IUser user) {
        return repository.findById(id, user, true);
    }


    public Uni<Brand> getBySlugName(String name, IUser user) {
        return repository.getBySlugName(name, user, false);
    }


}