package com.semantyca.aivox.service;

import com.semantyca.aivox.dto.ScriptDTO;
import com.semantyca.aivox.dto.filter.ScriptFilterDTO;
import com.semantyca.aivox.repository.ScriptRepository;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.filter.ScriptFilter;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class ScriptService extends AbstractService<Script, ScriptDTO> {
    private final ScriptRepository repository;
    private final SceneService scriptSceneService;
    private final PromptService promptService;

    protected ScriptService() {
        super();
        this.repository = null;
        this.scriptSceneService = null;
        this.promptService = null;
    }

    @Inject
    public ScriptService(UserService userService, ScriptRepository repository, SceneService scriptSceneService, PromptService promptService) {
        super(userService);
        this.repository = repository;
        this.scriptSceneService = scriptSceneService;
        this.promptService = promptService;
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


}
