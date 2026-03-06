package com.semantyca.aivox.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "aivox.messaging.broker")
public interface BrokerConfig {

    @WithDefault("localhost")
    String host();

    @WithDefault("5672")
    int port();

    @WithDefault("guest")
    String username();

    @WithDefault("guest")
    String password();

    @WithDefault("/")
    String virtualHost();

    QueueRequests queueRequests();

    Exchange exchange();

    interface QueueRequests {
        @WithDefault("queue-requests")
        String name();

        @WithDefault("true")
        boolean durable();

        @WithDefault("false")
        boolean exclusive();

        @WithDefault("false")
        boolean autoDelete();
    }

    interface Exchange {
        @WithDefault("queue-requests")
        String name();

        @WithDefault("direct")
        String type();

        @WithDefault("true")
        boolean durable();
    }
}