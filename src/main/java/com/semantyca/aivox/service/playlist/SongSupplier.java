package com.semantyca.aivox.service.playlist;

import com.semantyca.aivox.model.soundfragment.SoundFragment;
import com.semantyca.aivox.service.BrandService;
import com.semantyca.aivox.service.SoundFragmentBrandService;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class SongSupplier implements ISupplier {
    protected static final int CACHE_TTL_MINUTES = 5;
    private final SoundFragmentBrandService soundFragmentBrandService;
    private final BrandService brandService;
    private final Map<String, CachedBrandData> brandCache = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public SongSupplier(SoundFragmentBrandService soundFragmentBrandService,
                        BrandService brandService) {
        this.soundFragmentBrandService = soundFragmentBrandService;
        this.brandService = brandService;
    }

    @Override
    public Uni<List<SoundFragment>> getBrandSongs(String brandName, UUID brandId, PlaylistItemType fragmentType, int quantity, List<UUID> excludedIds) {
        return getBrandDataCached(brandName, brandId, fragmentType)
                .map(fragments -> {
                    if (fragments.isEmpty()) return List.of();

                    List<SoundFragment> shuffled = new ArrayList<>(fragments);
                    Collections.shuffle(shuffled, secureRandom);

                    return shuffled.stream()
                            .filter(f -> excludedIds == null || !excludedIds.contains(f.getId()))
                            .limit(quantity)
                            .collect(Collectors.toList());
                });
    }

    private Uni<List<SoundFragment>> getBrandDataCached(String brandName, UUID brandId, PlaylistItemType fragmentType) {
        String cacheKey = brandName + "_" + fragmentType;
        CachedBrandData cached = brandCache.get(cacheKey);

        if (cached != null && !cached.isExpired()) {
            return Uni.createFrom().item(cached.fragments);
        }

        if (brandId != null) {
            return soundFragmentBrandService.getBrandSongsRandomPage(brandId, fragmentType)
                    .flatMap(f -> f.isEmpty()
                            ? soundFragmentBrandService.getBrandSongs(brandId, fragmentType)
                            : Uni.createFrom().item(f))
                    .map(fragments -> {
                        Collections.shuffle(fragments, secureRandom);
                        brandCache.put(cacheKey, new CachedBrandData(brandId, fragments));
                        return fragments;
                    });
        }

        return brandService.getBySlugName(brandName)
                .onItem().transformToUni(brand -> {
                    if (brand == null) {
                        return Uni.createFrom().failure(
                                new IllegalArgumentException("Brand not found: " + brandName));
                    }
                    UUID resolvedBrandId = brand.getId();

                    return soundFragmentBrandService.getBrandSongsRandomPage(resolvedBrandId, fragmentType)
                            .flatMap(f -> f.isEmpty()
                                    ? soundFragmentBrandService.getBrandSongs(resolvedBrandId, fragmentType)
                                    : Uni.createFrom().item(f))
                            .map(fragments -> {
                                Collections.shuffle(fragments, secureRandom);
                                brandCache.put(cacheKey, new CachedBrandData(resolvedBrandId, fragments));
                                return fragments;
                            });
                });
    }
}
