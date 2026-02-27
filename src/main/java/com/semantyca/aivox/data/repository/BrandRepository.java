package com.semantyca.aivox.data.repository;

import com.semantyca.aivox.data.Brand;
import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class BrandRepository implements PanacheRepository<Brand> {
    
    public Uni<Brand> findById(UUID id) {
        return find("id", id).firstResult();
    }
    
    public Uni<Brand> findBySlugName(String slugName) {
        return find("slugName", slugName).firstResult();
    }

}
