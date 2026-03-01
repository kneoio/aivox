package com.semantyca.aivox.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.aivox.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import io.kneo.core.repository.rls.RLSRepository;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class SoundFragmentBrandRepository extends SoundFragmentRepositoryAbstract {
    private final SecureRandom secureRandom = new SecureRandom();

    @Inject
    public SoundFragmentBrandRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<SoundFragment>> getBrandSongs(UUID brandId, PlaylistItemType fragmentType, final int limit, final int offset) {
        String sql = "SELECT t.* " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "WHERE bsf.brand_id = $1 AND t.archived = 0 AND t.type = $2 " +
                "ORDER BY bsf.played_by_brand_count";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        Tuple params = Tuple.of(brandId, fragmentType);

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, true, true, true))
                .concatenate()
                .collect().asList();
    }

    public Uni<List<SoundFragment>> getBrandSongsRandomPage(UUID brandId, PlaylistItemType type) {
        int limit = 200;
        int offset = secureRandom.nextInt(20) * limit;
        return getBrandSongs(brandId, type, limit, offset);
    }
}
