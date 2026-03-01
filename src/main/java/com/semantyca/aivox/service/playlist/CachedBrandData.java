package com.semantyca.aivox.service.playlist;

import com.semantyca.aivox.model.soundfragment.SoundFragment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

class CachedBrandData {
    final UUID brandId;
    final List<SoundFragment> fragments;
    final LocalDateTime timestamp;

    CachedBrandData(UUID brandId, List<SoundFragment> fragments) {
        this.brandId = brandId;
        this.fragments = fragments;
        this.timestamp = LocalDateTime.now();
    }

    boolean isExpired() {
        return timestamp.plusMinutes(SongSupplier.CACHE_TTL_MINUTES).isBefore(LocalDateTime.now());
    }
}
