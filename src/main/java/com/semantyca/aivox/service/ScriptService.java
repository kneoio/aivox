package com.semantyca.aivox.service;

import com.semantyca.aivox.dto.BrandScriptDTO;
import com.semantyca.aivox.dto.SceneDTO;
import com.semantyca.aivox.dto.ScriptDTO;
import com.semantyca.aivox.dto.filter.ScriptFilterDTO;
import com.semantyca.aivox.repository.ScriptRepository;
import com.semantyca.mixpla.model.BrandScript;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.filter.ScriptFilter;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ScriptService extends AbstractService<Script, ScriptDTO> {
    private final ScriptRepository repository;
    private final SceneService scriptSceneService;
    private final PromptService promptService;
    private final DraftService draftService;

    protected ScriptService() {
        super();
        this.repository = null;
        this.scriptSceneService = null;
        this.promptService = null;
        this.draftService = null;
    }

    @Inject
    public ScriptService(UserService userService, ScriptRepository repository, SceneService scriptSceneService, PromptService promptService, DraftService draftService) {
        super(userService);
        this.repository = repository;
        this.scriptSceneService = scriptSceneService;
        this.promptService = promptService;
        this.draftService = draftService;
    }

    public Uni<List<ScriptDTO>> getAllDTO(final int limit, final int offset, final IUser user) {
        return getAllDTO(limit, offset, user, null);
    }

    public Uni<List<ScriptDTO>> getAllDTO(final int limit, final int offset, final IUser user, final ScriptFilterDTO filterDTO) {
        assert repository != null;
        ScriptFilter filter = toFilter(filterDTO);
        return repository.getAll(limit, offset, false, user, filter)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<ScriptDTO>> unis = list.stream()
                            .map(script -> mapToDTO(script, user))
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    private ScriptFilter toFilter(ScriptFilterDTO dto) {
        if (dto == null) {
            return null;
        }

        ScriptFilter filter = new ScriptFilter();
        filter.setActivated(dto.isActivated());
        filter.setLabels(dto.getLabels());
        filter.setTimingMode(dto.getTimingMode());
        filter.setLanguageTag(dto.getLanguageTag());
        filter.setSearchTerm(dto.getSearchTerm());

        return filter;
    }

    public Uni<Integer> getAllCount(final IUser user) {
        return getAllCount(user, null);
    }

    public Uni<Integer> getAllCount(final IUser user, final ScriptFilterDTO filterDTO) {
        assert repository != null;
        ScriptFilter filter = toFilter(filterDTO);
        return repository.getAllCount(user, false, filter);
    }



    @Override
    public Uni<ScriptDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        assert repository != null;
        return repository.findById(id, user, false).chain(script -> mapToDTO(script, user));
    }

    public Uni<Script> getById(UUID id, IUser user) {
        assert repository != null;
        return repository.findById(id, user, false);
    }



    public Uni<Integer> archive(String id, IUser user) {
        assert repository != null;
        return repository.archive(UUID.fromString(id), user);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id), user);
    }

    private Uni<ScriptDTO> mapToDTO(Script script, IUser user) {
        return Uni.combine().all().unis(
                userService.getUserName(script.getAuthor()),
                userService.getUserName(script.getLastModifier())
        ).asTuple().map(tuple -> {
            ScriptDTO dto = new ScriptDTO();
            dto.setId(script.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(script.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(script.getLastModifiedDate());
            dto.setName(script.getName());
            dto.setSlugName(script.getSlugName());
            dto.setDefaultProfileId(script.getDefaultProfileId());
            dto.setDescription(script.getDescription());
            dto.setAccessLevel(script.getAccessLevel());
            dto.setLanguageTag(script.getLanguageTag().tag());
            dto.setLabels(script.getLabels());
            dto.setBrands(script.getBrands());
            dto.setTimingMode(script.getTimingMode().name());
            dto.setRequiredVariables(script.getRequiredVariables());
            return dto;
        });
    }


    private Uni<BrandScriptDTO> mapToDTO(BrandScript brandScript, IUser user) {
        return mapToDTO(brandScript.getScript(), user).map(scriptDTO -> {
            BrandScriptDTO dto = new BrandScriptDTO();
            dto.setId(brandScript.getId());
            dto.setDefaultBrandId(brandScript.getDefaultBrandId());
            dto.setRank(brandScript.getRank());
            dto.setActive(brandScript.isActive());
            dto.setScript(scriptDTO);
            dto.setRepresentedInBrands(brandScript.getRepresentedInBrands());
            return dto;
        });
    }


    private Uni<Void> processScenes(UUID scriptId, List<SceneDTO> sceneDTOs, IUser user) {
        assert scriptSceneService != null;
        return scriptSceneService.getAllByScript(scriptId, 1000, 0, user)
                .chain(existingScenes -> {
                    List<UUID> incomingSceneIds = sceneDTOs != null ? sceneDTOs.stream()
                            .map(SceneDTO::getId)
                            .filter(Objects::nonNull)
                            .toList() : List.of();

                    List<UUID> scenesToDelete = existingScenes.stream()
                            .map(SceneDTO::getId)
                            .filter(id -> !incomingSceneIds.contains(id))
                            .toList();

                    Uni<Void> deleteUni = scenesToDelete.isEmpty()
                            ? Uni.createFrom().voidItem()
                            : Uni.join().all(scenesToDelete.stream()
                                    .map(id -> scriptSceneService.delete(id.toString(), user))
                                    .collect(Collectors.toList()))
                                    .andFailFast()
                                    .replaceWithVoid();

                    if (sceneDTOs == null || sceneDTOs.isEmpty()) {
                        return deleteUni;
                    }

                    List<Uni<SceneDTO>> upsertUnis = sceneDTOs.stream()
                            .map(sceneDTO -> {
                                String sceneId = sceneDTO.getId() != null ? sceneDTO.getId().toString() : null;
                                return scriptSceneService.upsert(sceneId, scriptId, sceneDTO, user);
                            })
                            .collect(Collectors.toList());

                    return deleteUni.chain(() -> Uni.join().all(upsertUnis).andFailFast().replaceWithVoid());
                });
    }

}
