package com.semantyca.aivox.service.soundfragment;

import com.semantyca.aivox.dto.SoundFragmentDTO;
import com.semantyca.aivox.repository.soundfragment.SoundFragmentRepository;
import com.semantyca.core.model.user.SuperUser;
import com.semantyca.core.service.AbstractService;
import com.semantyca.core.service.UserService;
import com.semantyca.mixpla.model.soundfragment.SoundFragment;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@ApplicationScoped
public class SoundFragmentService extends AbstractService<SoundFragment, SoundFragmentDTO> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentService.class);

    private final SoundFragmentRepository repository;
    Validator validator;

    protected SoundFragmentService(UserService userService) {
        super(userService);
        this.repository = null;
    }

    @Inject
    public SoundFragmentService(UserService userService,
                                Validator validator,
                                SoundFragmentRepository repository
    ) {
        super(userService);
        this.validator = validator;
        this.repository = repository;
    }

    public Uni<SoundFragment> getById(UUID uuid) {
        assert repository != null;
        return repository.findById(uuid, SuperUser.ID, false, false, true);
    }

}
