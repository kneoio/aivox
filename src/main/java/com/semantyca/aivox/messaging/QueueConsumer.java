package com.semantyca.aivox.messaging;

import com.semantyca.aivox.service.QueueService;
import com.semantyca.mixpla.dto.queue.QueueMessageDTO;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

@ApplicationScoped
public class QueueConsumer {

    private static final Logger LOGGER = Logger.getLogger(QueueConsumer.class);

    @Inject
    QueueService queueService;

    @Incoming("queue-requests")
    public Uni<Void> consume(QueueMessageDTO message) {
        LOGGER.info("Consuming queue request for brand: " + message.getBrandName() +
                ", uploadId: " + message.getUploadId() +
                ", mergingMethod: " + message.getDto().getMergingMethod());

        return queueService.addToQueue(message.getBrandName(), message.getDto(), message.getUploadId())
                .onItem().invoke(result ->
                        LOGGER.info("Queue request completed for uploadId: " + message.getUploadId() + ", result: " + result)
                )
                .onFailure().invoke(e ->
                        LOGGER.error("Queue request failed for uploadId: " + message.getUploadId(), e)
                )
                .replaceWithVoid();
    }
}
