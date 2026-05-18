package com.quanla.sagademo.inventory.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanla.sagademo.common.event.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

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
        repository.save(OutboxEvent.builder()
                .id(UUID.randomUUID())
                .sagaId(sagaId)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .topic(topic)
                .messageKey(messageKey)
                .eventType(eventType)
                .payload(toJson(envelope))
                .occurredAt(Instant.now())
                .build());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }
}
