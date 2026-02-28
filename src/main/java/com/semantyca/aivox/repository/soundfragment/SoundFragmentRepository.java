package com.semantyca.aivox.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.aivox.model.soundfragment.SoundFragment;
import com.semantyca.aivox.model.soundfragment.SoundFragmentFilter;
import com.semantyca.aivox.repository.MixplaNameResolver;
import com.semantyca.core.repository.file.HetznerStorage;
import com.semantyca.core.repository.file.IFileStorage;
import com.semantyca.mixpla.model.cnst.PlaylistItemType;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.semantyca.aivox.repository.MixplaNameResolver.SOUND_FRAGMENT;

@ApplicationScoped
public class SoundFragmentRepository extends SoundFragmentRepositoryAbstract {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentRepository.class);
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(SOUND_FRAGMENT);

    private final IFileStorage fileStorage;
    private final SoundFragmentFileHandler fileHandler;
    private final SoundFragmentQueryBuilder queryBuilder;
    private final SoundFragmentBrandAssociationHandler brandHandler;
    private final SecureRandom secureRandom = new SecureRandom();

    public SoundFragmentRepository() {
        super();
        this.fileStorage = null;
        this.fileHandler = null;
        this.queryBuilder = null;
        this.brandHandler = null;
    }

    @Inject
    public SoundFragmentRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository,
                                   HetznerStorage fileStorage, SoundFragmentFileHandler fileHandler,
                                   SoundFragmentQueryBuilder queryBuilder, SoundFragmentBrandAssociationHandler brandHandler) {
        super(client, mapper, rlsRepository);
        this.fileStorage = fileStorage;
        this.fileHandler = fileHandler;
        this.queryBuilder = queryBuilder;
        this.brandHandler = brandHandler;
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

    public Uni<Integer> getAllCount(IUser user, SoundFragmentFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId() + " AND t.archived = 0";

        if (filter != null && filter.isActivated()) {
            assert queryBuilder != null;
            sql += queryBuilder.buildFilterConditions(filter);
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm()))
                    .onItem().transform(rows -> rows.iterator().next().getInteger(0));
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<SoundFragment> findById(UUID uuid, Long userID, boolean includeGenres, boolean includeFiles) {
        String sql = "SELECT theTable.*, rls.*" +
                String.format(" FROM %s theTable JOIN %s rls ON theTable.id = rls.entity_id ", entityData.getTableName(), entityData.getRlsName()) +
                "WHERE rls.reader = $1 AND theTable.id = $2 AND theTable.archived = 0";

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

    public Uni<SoundFragment> findById(UUID uuid) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE id = $1";

        return client.preparedQuery(sql)
                .execute(Tuple.of(uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        Row row = iterator.next();
                        return from(row, false, false, false);
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(uuid));
                    }
                });
    }


    public Uni<List<SoundFragment>> getBrandSongsRandomPage(UUID brandId, PlaylistItemType type) {
        int limit = 200;
        int offset = secureRandom.nextInt(20) * limit;
        SoundFragmentBrandRepository brandRepository =
                new SoundFragmentBrandRepository(client, mapper, rlsRepository);

        return brandRepository.getBrandSongs(brandId, type, limit, offset);
    }

    public Uni<List<SoundFragment>> findByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        String placeholders = ids.stream()
                .map(id -> "'" + id.toString() + "'")
                .collect(Collectors.joining(","));
        String sql = "SELECT t.* FROM " + entityData.getTableName() + " t " +
                "WHERE t.id IN (" + placeholders + ") AND t.archived = 0";
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, false, false, false))
                .concatenate()
                .collect().asList();
    }

    public Uni<List<SoundFragment>> findByFilter(UUID brandId, SoundFragmentFilter filter, int limit) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.findByFilter(brandId, filter, limit);
    }


    public Uni<List<SoundFragment>> getBrandSongs(UUID brandId, PlaylistItemType fragmentType) {
        SoundFragmentBrandRepository brandRepository = new SoundFragmentBrandRepository(client, mapper, rlsRepository);
        return brandRepository.getBrandSongs(brandId, fragmentType, 200, 0);
    }

}