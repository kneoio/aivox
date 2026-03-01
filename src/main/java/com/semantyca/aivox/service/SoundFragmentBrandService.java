package com.semantyca.aivox.service;

import com.semantyca.aivox.model.soundfragment.SoundFragment;
import com.semantyca.aivox.repository.soundfragment.SoundFragmentBrandRepository;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SoundFragmentBrandService {

    private final SoundFragmentBrandRepository soundFragmentBrandRepository;

    @Inject
    public SoundFragmentBrandService(SoundFragmentBrandRepository soundFragmentBrandRepository) {
        this.soundFragmentBrandRepository = soundFragmentBrandRepository;
    }

    public Uni<List<SoundFragment>> getBrandSongs(UUID brandId, PlaylistItemType fragmentType) {
        return soundFragmentBrandRepository.getBrandSongs(brandId, fragmentType, 200, 0);
    }

    public Uni<List<SoundFragment>> getBrandSongsRandomPage(UUID brandId, PlaylistItemType type){
        return soundFragmentBrandRepository.getBrandSongs(brandId, type, 200, 0);
    }

}
