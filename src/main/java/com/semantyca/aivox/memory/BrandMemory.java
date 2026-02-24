package com.semantyca.aivox.memory;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Setter
@Getter
@Entity
@Table(name = "brand_memory")
public class BrandMemory extends PanacheEntityBase {
    
    @EmbeddedId
    private BrandMemoryId id;
    
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;
    
    // Convenience methods
    public String getBrand() {
        return id != null ? id.getBrand() : null;
    }
    
    public LocalDate getDate() {
        return id != null ? id.getDate() : null;
    }
    
    public void setBrand(String brand) {
        if (id == null) id = new BrandMemoryId();
        id.setBrand(brand);
    }
    
    public void setDate(LocalDate date) {
        if (id == null) id = new BrandMemoryId();
        id.setDate(date);
    }
}
