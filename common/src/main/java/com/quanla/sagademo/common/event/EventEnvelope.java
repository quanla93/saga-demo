package com.quanla.sagademo.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Generic envelope wrapping every Kafka message.
 * <p>
 * - {@code messageId} is the dedup key checked by the Inbox table on the consumer side.
 * - {@code sagaId} ties all events of a single saga together for tracing / state lookup.
 * - {@code type} drives polymorphic dispatch on consumers without relying on Jackson type info.
 * - {@code payload} carries the event body as raw JSON so producers and consumers can
 *   evolve independently.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope(
        UUID messageId,
        UUID sagaId,
        String type,
        Instant occurredAt,
        String payload
) {
    public static EventEnvelope of(UUID sagaId, String type, String payload) {
        return new EventEnvelope(UUID.randomUUID(), sagaId, type, Instant.now(), payload);
    }
}