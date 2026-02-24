package com.semantyca.aivox;

import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.semantyca.aivox.rest.StreamingResource;

@ApplicationScoped
public class AivoxApplication {

    @Inject
    StreamingResource streamingResource;

    void setupRoutes(@Observes StartupEvent event, Router router) {
        streamingResource.setupRoutes(router);
    }
}
