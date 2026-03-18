package com.semantyca.aivox;

import com.semantyca.aivox.rest.CommandResource;
import com.semantyca.aivox.rest.InfoResource;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.semantyca.aivox.rest.DebugResource;
import com.semantyca.aivox.rest.StreamingResource;

@ApplicationScoped
public class AivoxApplication {

    @Inject
    StreamingResource streamingResource;

    @Inject
    InfoResource infoResource;
    
    @Inject
    DebugResource debugResource;

    @Inject
    CommandResource commandResource;


    void setupRoutes(@Observes Router router) {
        streamingResource.setupRoutes(router);
        infoResource.setupRoutes(router);
        commandResource.setupRoutes(router);
        debugResource.setupRoutes(router);
    }
}
