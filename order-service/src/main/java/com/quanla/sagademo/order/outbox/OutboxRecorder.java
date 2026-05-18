package com.quanla.sagademo.order.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanla.sagademo.common.event.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Inserts outbox rows in the SAME transaction as business state changes.
 * <p>
 * Callers must invoke {@link #record} from inside an @Transactional method that
 * also writes the domain entity. The scheduled {@link OutboxPublisher} picks the
 * rows up afterwards and publishes them to Kafka.
 */
@Component
@RequiredArgsConstructor
public class OutboxRecorder {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public void record(UUID sagaId,
                       String aggregateType,
                       UUID aggregateId,
                       String topic,
                       String messageKey,
                       String eventType,
                       Object payload) {
        String payloadJson = toJson(payload);
        EventEnvelope envelope = EventEnvelope.of(sagaId, eventType, payloadJson);
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .sagaId(sagaId)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .topic(topic)
                .messageKey(messageKey)
                .eventType(eventType)
                .payload(toJson(envelope))
                .occurredAt(Instant.now())
                .build();
        repository.save(event);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}