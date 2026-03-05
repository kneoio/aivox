package com.semantyca.aivox.service;

import com.semantyca.aivox.dto.DraftDTO;
import com.semantyca.aivox.dto.filter.DraftFilterDTO;
import com.semantyca.aivox.repository.ScriptRepository;
import com.semantyca.aivox.repository.draft.DraftRepository;
import com.semantyca.aivox.service.live.scripting.DraftFactory;
import com.semantyca.aivox.service.soundfragment.SoundFragmentService;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.mixpla.model.Draft;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class DraftService extends AbstractService<Draft, DraftDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DraftService.class);

    private final DraftRepository repository;
    private final ScriptRepository scriptRepository;
    private final DraftFactory draftFactory;
    private final SoundFragmentService soundFragmentService;
    private final AiAgentService aiAgentService;
    private final BrandService brandService;

    @Inject
    public DraftService(UserService userService, DraftRepository repository, ScriptRepository scriptRepository,
                        DraftFactory draftFactory, SoundFragmentService soundFragmentService,
                        AiAgentService aiAgentService, BrandService brandService) {
        super(userService);
        this.repository = repository;
        this.scriptRepository = scriptRepository;
        this.draftFactory = draftFactory;
        this.soundFragmentService = soundFragmentService;
        this.aiAgentService = aiAgentService;
        this.brandService = brandService;
    }

    public Uni<List<Draft>> getAll() {
        return repository.getAll(0, 0, false, SuperUser.build(), null);
    }

    public Uni<List<DraftDTO>> getAll(final int limit, final int offset, final IUser user, final DraftFilterDTO filter) {
        return repository.getAll(limit, offset, false, user, filter)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    } else {
                        List<Uni<DraftDTO>> unis = list.stream()
                                .map(this::mapToDTO)
                                .collect(Collectors.toList());
                        return Uni.join().all(unis).andFailFast();
                    }
                });
    }

    public Uni<Integer> getAllCount(final IUser user, final DraftFilterDTO filter) {
        return repository.getAllCount(user, false, filter);
    }

    public Uni<Draft> getById(UUID id, IUser user) {
        return repository.findById(id, user, true);
    }

    public Uni<List<Draft>> getByIds(List<UUID> ids, IUser user) {
        List<Uni<Draft>> draftUnis = ids.stream()
                .map(id -> repository.findById(id, user, false)
                        .onFailure().recoverWithNull())
                .collect(Collectors.toList());
        
        return Uni.join().all(draftUnis).andCollectFailures()
                .map(drafts -> drafts.stream()
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList()));
    }

    public Uni<List<Draft>> getByIds(List<UUID> ids) {
        return getByIds(ids, SuperUser.build());
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        return repository.archive(UUID.fromString(id), user);
    }

    @Override
    public Uni<DraftDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        return repository.findById(id, user, false).chain(this::mapToDTO);
    }


    public Uni<Draft> insert(Draft entity, IUser user) {
        return repository.insert(entity, user);
    }

    public Uni<Draft> update(UUID id, Draft entity, IUser user) {
        return repository.update(id, entity, user);
    }

    public Uni<Integer> archive(String id, IUser user) {
        return repository.archive(UUID.fromString(id), user);
    }

    public Uni<Draft> findByMasterAndLanguage(UUID masterId, LanguageTag languageTag, boolean includeArchived) {
        return repository.findByMasterAndLanguage(masterId, languageTag, includeArchived);
    }

    private Uni<DraftDTO> mapToDTO(Draft doc) {
        return Uni.combine().all().unis(
                userService.getUserName(doc.getAuthor()),
                userService.getUserName(doc.getLastModifier())
        ).asTuple().map(tuple -> {
            DraftDTO dto = new DraftDTO();
            dto.setId(doc.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(doc.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(doc.getLastModifiedDate());
            dto.setTitle(doc.getTitle());
            dto.setContent(doc.getContent());
            dto.setDescription(doc.getDescription());
            dto.setLanguageTag(doc.getLanguageTag().tag());
            dto.setArchived(doc.getArchived());
            dto.setEnabled(doc.isEnabled());
            dto.setMaster(doc.isMaster());
            dto.setLocked(doc.isLocked());
            dto.setMasterId(doc.getMasterId());
            dto.setVersion(doc.getVersion());
            return dto;
        });
    }

    private Draft buildEntity(DraftDTO dto) {
        Draft doc = new Draft();
        doc.setTitle(dto.getTitle());
        doc.setContent(dto.getContent());
        doc.setDescription(dto.getDescription());
        doc.setLanguageTag(LanguageTag.fromTag(dto.getLanguageTag()));
        doc.setArchived(dto.getArchived());
        doc.setEnabled(dto.isEnabled());
        doc.setMaster(dto.isMaster());
        doc.setLocked(dto.isLocked());
        doc.setMasterId(dto.getMasterId());
        doc.setVersion(dto.getVersion());
        return doc;
    }




}
