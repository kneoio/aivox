package com.semantyca.aivox.service;

import com.semantyca.aivox.data.Brand;
import com.semantyca.aivox.data.repository.BrandRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@ApplicationScoped
public class BrandService {
    
    private static final Logger logger = LoggerFactory.getLogger(BrandService.class);
    
    @Inject
    BrandRepository brandRepository;
    
    public Uni<Brand> getBrandById(UUID id) {
        return brandRepository.findById(id)
                .onItem().ifNull().failWith(() -> new IllegalArgumentException("Brand not found with id: " + id));
    }
    
    public Uni<Brand> getBrandBySlugName(String slugName) {
        return brandRepository.findBySlugName(slugName)
                .onItem().ifNull().failWith(() -> new IllegalArgumentException("Brand not found with slugName: " + slugName));
    }
}
