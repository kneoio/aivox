package com.semantyca.aivox.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantyca.aivox.service.QueueService;
import com.semantyca.mixpla.dto.queue.livestream.SongQueueMessageDTO;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QueueConsumerTest {

    @Mock
    QueueService queueService;

    @Mock
    Message<byte[]> message;

    @InjectMocks
    QueueConsumer queueConsumer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void consume_shouldProcessMessageForAssignedBrand() throws Exception {
        // Given
        SongQueueMessageDTO dto = new SongQueueMessageDTO();
        dto.setBrandSlug("lumisonic");
        dto.setSceneId(UUID.randomUUID());
        dto.setMessageId(UUID.randomUUID());

        byte[] payload = objectMapper.writeValueAsBytes(dto);

        when(message.getPayload()).thenReturn(payload);
        when(message.ack()).thenReturn(CompletableFuture.completedFuture(null));
        when(queueService.addToQueue(any(SongQueueMessageDTO.class))).thenReturn(Uni.createFrom().item(true));

        // When
        Uni<Void> result = queueConsumer.consume(message);

        // Then
        result.subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verify(queueService).addToQueue(any(SongQueueMessageDTO.class));
        verify(message).ack();
        verify(message, never()).nack(any());
    }

    @Test
    void consume_shouldNackForUnassignedBrand() throws Exception {
        // Given
        SongQueueMessageDTO dto = new SongQueueMessageDTO();
        dto.setBrandSlug("other-brand");
        dto.setSceneId(UUID.randomUUID());
        dto.setMessageId(UUID.randomUUID());

        byte[] payload = objectMapper.writeValueAsBytes(dto);

        when(message.getPayload()).thenReturn(payload);
        when(message.nack(any(RuntimeException.class))).thenReturn(CompletableFuture.completedFuture(null));

        // When
        Uni<Void> result = queueConsumer.consume(message);

        // Then
        result.subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verify(queueService, never()).addToQueue(any());
        verify(message, never()).ack();
        verify(message).nack(any(RuntimeException.class));
    }

    @Test
    void consume_shouldNackOnDeserializationFailure() {
        // Given - invalid JSON
        byte[] payload = "invalid json".getBytes();

        when(message.getPayload()).thenReturn(payload);
        when(message.nack(any(Exception.class))).thenReturn(CompletableFuture.completedFuture(null));

        // When
        Uni<Void> result = queueConsumer.consume(message);

        // Then
        result.subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verify(queueService, never()).addToQueue(any());
        verify(message, never()).ack();
        verify(message).nack(any(Exception.class));
    }

    @Test
    void consume_shouldNackOnQueueServiceFailure() throws Exception {
        // Given
        SongQueueMessageDTO dto = new SongQueueMessageDTO();
        dto.setBrandSlug("lumisonic");
        dto.setSceneId(UUID.randomUUID());
        dto.setMessageId(UUID.randomUUID());

        byte[] payload = objectMapper.writeValueAsBytes(dto);

        RuntimeException expectedError = new RuntimeException("Queue service failed");

        when(message.getPayload()).thenReturn(payload);
        when(queueService.addToQueue(any(SongQueueMessageDTO.class)))
                .thenReturn(Uni.createFrom().failure(expectedError));
        when(message.nack(any(Throwable.class))).thenReturn(CompletableFuture.completedFuture(null));

        // When
        Uni<Void> result = queueConsumer.consume(message);

        // Then
        result.subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted();

        verify(queueService).addToQueue(any(SongQueueMessageDTO.class));
        verify(message, never()).ack();
        verify(message).nack(any(Throwable.class));
    }
}
