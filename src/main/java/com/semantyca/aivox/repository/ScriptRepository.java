package com.semantyca.aivox.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.core.model.ScriptVariable;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.model.BrandScript;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.cnst.SceneTimingMode;
import com.semantyca.mixpla.model.filter.ScriptFilter;
import com.semantyca.mixpla.repository.MixplaNameResolver;
import io.kneo.core.model.embedded.DocumentAccessInfo;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.semantyca.mixpla.repository.MixplaNameResolver.SCRIPT;

@ApplicationScoped
public class ScriptRepository extends AsyncRepository {
    private static final EntityData entityData = MixplaNameResolver.create().getEntityNames(SCRIPT);

    @Inject
    public ScriptRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<Script>> getAll(int limit, int offset, boolean includeArchived, final IUser user, final ScriptFilter filter) {
        String sql = """
                    SELECT t.*, rls.*, ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = t.id) AS labels
                    FROM %s t
                    JOIN %s rls ON t.id = rls.entity_id
                    WHERE rls.reader = %s
                """.formatted(entityData.getTableName(), entityData.getRlsName(), user.getId());

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        sql += " ORDER BY t.last_mod_date DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm().trim()))
                    .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                    .onItem().transform(this::from)
                    .collect().asList();
        }

        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived) {
        return getAllCount(user, includeArchived, null);
    }

    public Uni<Integer> getAllCount(IUser user, boolean includeArchived, ScriptFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t, " + entityData.getRlsName() + " rls " +
                "WHERE t.id = rls.entity_id AND rls.reader = " + user.getId();

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm().trim()))
                    .onItem().transform(rows -> rows.iterator().next().getInteger(0));
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    private String buildFilterConditions(ScriptFilter filter) {
        StringBuilder conditions = new StringBuilder();

        if (filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            conditions.append(" AND (");
            conditions.append("t.name ILIKE '%' || $1 || '%' ");
            conditions.append("OR t.description ILIKE '%' || $1 || '%'");
            conditions.append(")");
        }

        if (filter.getLabels() != null && !filter.getLabels().isEmpty()) {
            conditions.append(" AND EXISTS (SELECT 1 FROM mixpla_script_labels sl2 WHERE sl2.script_id = t.id AND sl2.label_id IN (");
            for (int i = 0; i < filter.getLabels().size(); i++) {
                if (i > 0) {
                    conditions.append(", ");
                }
                conditions.append("'").append(filter.getLabels().get(i).toString()).append("'");
            }
            conditions.append("))");
        }

        if (filter.getTimingMode() != null) {
            conditions.append(" AND t.timing_mode = '").append(filter.getTimingMode().name()).append("'");
        }

        if (filter.getLanguageTag() != null) {
            conditions.append(" AND t.language_tag = '").append(filter.getLanguageTag().tag()).append("'");
        }

        return conditions.toString();
    }

    public Uni<List<Script>> getAllShared(int limit, int offset, final IUser user, final ScriptFilter filter) {
        String sql = """
                    SELECT t.*, ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = t.id) AS labels
                    FROM %s t
                    WHERE (t.access_level = 1 OR EXISTS (
                        SELECT 1 FROM %s rls WHERE rls.entity_id = t.id AND rls.reader = %s
                    )) AND t.archived = 0
                """.formatted(entityData.getTableName(), entityData.getRlsName(), user.getId());

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        sql += " ORDER BY t.last_mod_date DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm().trim()))
                    .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                    .onItem().transform(this::from)
                    .collect().asList();
        }

        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> getAllSharedCount(IUser user, ScriptFilter filter) {
        String sql = "SELECT COUNT(*) FROM " + entityData.getTableName() + " t " +
                "WHERE (t.access_level = 1 OR EXISTS (SELECT 1 FROM " + entityData.getRlsName() +
                " rls WHERE rls.entity_id = t.id AND rls.reader = " + user.getId() + ")) AND t.archived = 0";

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            return client.preparedQuery(sql)
                    .execute(Tuple.of(filter.getSearchTerm().trim()))
                    .onItem().transform(rows -> rows.iterator().next().getInteger(0));
        }

        return client.query(sql)
                .execute()
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    public Uni<Script> findById(UUID id, IUser user, boolean includeArchived) {
        String sql = """
                    SELECT theTable.*, rls.*, ARRAY(SELECT label_id FROM mixpla_script_labels sl WHERE sl.script_id = theTable.id) AS labels
                    FROM %s theTable
                    JOIN %s rls ON theTable.id = rls.entity_id
                    WHERE rls.reader = $1 AND theTable.id = $2
                """.formatted(entityData.getTableName(), entityData.getRlsName());

        if (!includeArchived) {
            sql += " AND theTable.archived = 0";
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId(), id))
                .onItem().transform(RowSet::iterator)
                .onItem().transformToUni(iterator -> {
                    if (iterator.hasNext()) {
                        return Uni.createFrom().item(from(iterator.next()));
                    } else {
                        return Uni.createFrom().failure(new DocumentHasNotFoundException(id));
                    }
                });
    }


    private Script from(Row row) {
        Script doc = new Script();
        setDefaultFields(doc, row);
        doc.setName(row.getString("name"));
        doc.setSlugName(row.getString("slug_name"));
        doc.setDefaultProfileId(row.getUUID("default_profile_id"));
        doc.setDescription(row.getString("description"));
        doc.setAccessLevel(row.getInteger("access_level"));
        doc.setArchived(row.getInteger("archived"));
        String lang = row.getString("language_tag");
        doc.setLanguageTag(LanguageTag.fromTag(lang));
        String timingMode = row.getString("timing_mode");
        if (timingMode != null) {
            doc.setTimingMode(SceneTimingMode.valueOf(timingMode));
        }

        Object[] arr = row.getArrayOfUUIDs("labels");
        if (arr != null && arr.length > 0) {
            List<UUID> labels = new ArrayList<>();
            for (Object o : arr) {
                labels.add((UUID) o);
            }
            doc.setLabels(labels);
        }

        JsonArray requiredVarsJson = row.getJsonArray("required_variables");
        if (requiredVarsJson != null && !requiredVarsJson.isEmpty()) {
            try {
                List<ScriptVariable> vars = mapper.readValue(requiredVarsJson.encode(), new TypeReference<>() {
                });
                doc.setRequiredVariables(vars);
            } catch (JsonProcessingException e) {
                doc.setRequiredVariables(new ArrayList<>());
            }
        }
        return doc;
    }

    public Uni<Integer> archive(UUID id, IUser user) {
        return archive(id, entityData, user);
    }

    public Uni<Integer> delete(UUID id, IUser user) {
        return delete(id, user, false);
    }

    public Uni<Integer> delete(UUID id, IUser user, boolean cascade) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), id)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[1]) {
                        return Uni.createFrom().failure(
                                new DocumentModificationAccessException("User does not have delete permission", user.getUserName(), id)
                        );
                    }

                    if (!cascade) {
                        String checkScenesSql = "SELECT COUNT(*) FROM mixpla_script_scenes WHERE script_id = $1";
                        return client.preparedQuery(checkScenesSql)
                                .execute(Tuple.of(id))
                                .onItem().transform(rows -> rows.iterator().next().getInteger(0))
                                .onItem().transformToUni(sceneCount -> {
                                    if (sceneCount != null && sceneCount > 0) {
                                        return Uni.createFrom().failure(new IllegalStateException(
                                                "Cannot delete script: it has " + sceneCount + " scene(s)"
                                        ));
                                    }
                                    return performDelete(id);
                                });
                    }

                    return performDelete(id);
                });
    }

    private Uni<Integer> performDelete(UUID id) {
        return client.withTransaction(tx -> {
            String deleteScenePromptsSql = "DELETE FROM mixpla__script_scene_actions WHERE script_scene_id IN (SELECT id FROM mixpla_script_scenes WHERE script_id = $1)";
            String deleteScenesSql = "DELETE FROM mixpla_script_scenes WHERE script_id = $1";
            String deleteLabelsSql = "DELETE FROM mixpla_script_labels WHERE script_id = $1";
            String deleteRlsSql = String.format("DELETE FROM %s WHERE entity_id = $1", entityData.getRlsName());
            String deleteDocSql = String.format("DELETE FROM %s WHERE id = $1", entityData.getTableName());

            return tx.preparedQuery(deleteScenePromptsSql)
                    .execute(Tuple.of(id))
                    .onItem().transformToUni(ignored ->
                            tx.preparedQuery(deleteScenesSql).execute(Tuple.of(id))
                    )
                    .onItem().transformToUni(ignored ->
                            tx.preparedQuery(deleteLabelsSql).execute(Tuple.of(id))
                    )
                    .onItem().transformToUni(ignored ->
                            tx.preparedQuery(deleteRlsSql).execute(Tuple.of(id))
                    )
                    .onItem().transformToUni(ignored ->
                            tx.preparedQuery(deleteDocSql).execute(Tuple.of(id))
                    )
                    .onItem().transform(RowSet::rowCount);
        });
    }


    private BrandScript createBrandScript(Row row, UUID brandId) {
        BrandScript brandScript = new BrandScript();
        brandScript.setId(row.getUUID("id"));
        brandScript.setDefaultBrandId(brandId);
        brandScript.setRank(row.getInteger("rank"));
        brandScript.setActive(row.getBoolean("active"));

        JsonObject userVarsJson = row.getJsonObject("user_variables");
        if (userVarsJson != null && !userVarsJson.isEmpty()) {
            try {
                Map<String, Object> userVars = mapper.readValue(userVarsJson.encode(), new TypeReference<>() {
                });
                brandScript.setUserVariables(userVars);
            } catch (JsonProcessingException e) {
                brandScript.setUserVariables(null);
            }
        }
        return brandScript;
    }



    public Uni<Void> patchRequiredVariables(UUID scriptId, List<ScriptVariable> requiredVariables) {
        String sql = "UPDATE " + entityData.getTableName() + " SET required_variables = $1 WHERE id = $2";
        JsonArray jsonArray = null;
        if (requiredVariables != null && !requiredVariables.isEmpty()) {
            try {
                jsonArray = new JsonArray(mapper.writeValueAsString(requiredVariables));
            } catch (JsonProcessingException e) {
                return Uni.createFrom().failure(e);
            }
        }
        return client.preparedQuery(sql)
                .execute(Tuple.of(jsonArray, scriptId))
                .replaceWithVoid();
    }

    public Uni<List<UUID>> findScriptIdsByDraftId(UUID draftId) {
        String sql = "SELECT DISTINCT ss.script_id FROM mixpla_script_scenes ss " +
                "JOIN mixpla__script_scene_actions ssa ON ssa.script_scene_id = ss.id " +
                "JOIN mixpla_prompts p ON p.id = ssa.prompt_id " +
                "WHERE p.draft_id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(draftId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("script_id"))
                .collect().asList();
    }

    public Uni<List<UUID>> findDraftIdsForScript(UUID scriptId) {
        String sql = "SELECT DISTINCT p.draft_id FROM mixpla_script_scenes ss " +
                "JOIN mixpla__script_scene_actions ssa ON ssa.script_scene_id = ss.id " +
                "JOIN mixpla_prompts p ON p.id = ssa.prompt_id " +
                "WHERE ss.script_id = $1 AND p.draft_id IS NOT NULL";
        return client.preparedQuery(sql)
                .execute(Tuple.of(scriptId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(row -> row.getUUID("draft_id"))
                .collect().asList();
    }
}
