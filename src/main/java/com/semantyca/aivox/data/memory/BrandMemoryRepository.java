package com.semantyca.aivox.data.memory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;

@ApplicationScoped
public class BrandMemoryRepository {
    
    public Uni<BrandMemory> findByBrandAndDate(String brand, LocalDate date) {
        return BrandMemory.findById(new BrandMemoryId(brand, date));
    }
    
    public Uni<BrandMemory> save(BrandMemory memory) {
        return memory.persistAndFlush().replaceWith(memory);
    }
}
