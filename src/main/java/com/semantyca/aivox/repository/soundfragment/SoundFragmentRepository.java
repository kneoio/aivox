package com.semantyca.aivox.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.FileMetadata;
import com.semantyca.core.model.user.IUser;
import com.semantyca.core.repository.exception.DocumentHasNotFoundException;
import com.semantyca.core.repository.rls.RLSRepository;
import com.semantyca.core.repository.table.EntityData;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import com.semantyca.mixpla.model.filter.SoundFragmentFilter;
import com.semantyca.mixpla.model.soundfragment.BrandSoundFragment;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

import static com.semantyca.mixpla.repository.MixplaNameResolver.SOUND_FRAGMENT;


@ApplicationScoped
public class SoundFragmentRepository extends SoundFragmentRepositoryAbstract {
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(SOUND_FRAGMENT);
    private final SoundFragmentQueryBuilder queryBuilder;
    private final SoundFragmentFileHandler fileHandler;

    public SoundFragmentRepository(SoundFragmentFileHandler fileHandler) {
        super();
        this.fileHandler = fileHandler;
        this.queryBuilder = null;
    }

    @Inject
    public SoundFragmentRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository,
                                   SoundFragmentQueryBuilder queryBuilder, SoundFragmentFileHandler fileHandler) {
        super(client, mapper, rlsRepository);
        this.queryBuilder = queryBuilder;
        this.fileHandler = fileHandler;
    }

    public Uni<List<SoundFragment>> getAll(final int limit, final int offset,
                                           final IUser user, final SoundFragmentFilter filter) {
        assert queryBuilder != null;
        String sql = queryBuilder.buildGetAllQuery(entityData.getTableName(), entityData.getRlsName(),
                user, false, filter, limit, offset);

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm()))
                    .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                    .onItem().transformToUni(row -> from(row, false, false, false))
                    .concatenate()
                    .collect().asList();
        }

        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, false, false, false))
                .concatenate()
                .collect().asList();
    }


    public Uni<SoundFragment> findById(UUID uuid, Long userID, boolean includeArchived, boolean includeGenres, boolean includeFiles) {
        String sql = "SELECT theTable.*, rls.*" +
                String.format(" FROM %s theTable JOIN %s rls ON theTable.id = rls.entity_id ", entityData.getTableName(), entityData.getRlsName()) +
                "WHERE rls.reader = $1 AND theTable.id = $2";
        if (!includeArchived) {
            sql += " AND theTable.archived = 0";
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(userID, uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return from(row, includeGenres, includeFiles, true);
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(uuid));
                    }
                });
    }


    public Uni<FileMetadata> getFirstFile(UUID id) {
        assert fileHandler != null;
        return fileHandler.getFirstFile(id);
    }
}