package com.semantyca.aivox.service.playlist;

import com.semantyca.aivox.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.UUID;

public interface ISupplier {


    Uni<List<SoundFragment>> getBrandSongs(String brandSlug, UUID brandId, PlaylistItemType playlistItemType, int quantityToFetch, List<UUID> excludedIds);
}