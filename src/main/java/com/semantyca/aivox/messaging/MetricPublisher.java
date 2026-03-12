package com.semantyca.aivox.messaging;

import com.semantyca.mixpla.dto.queue.metric.MetricEventDTO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

@ApplicationScoped
public class MetricPublisher {

    @Inject
    @Channel("metrics")
    Emitter<byte[]> metricsEmitter;

    public Uni<Void> publish(MetricEventDTO event) {
        // implement later
        return Uni.createFrom().voidItem();
    }
}