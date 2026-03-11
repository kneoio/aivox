package com.semantyca.aivox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.officeframe.service.GenreService;
import io.kneo.officeframe.service.LabelService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RefService {
    private final LabelService labelService;
    private final GenreService genreService;
    private final ObjectMapper objectMapper;

    @Inject
    public RefService(LabelService labelService, GenreService genreService) {
        this.labelService = labelService;
        this.genreService = genreService;
        this.objectMapper = new ObjectMapper();
    }




}
