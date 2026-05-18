package com.quanla.sagademo.order.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanla.sagademo.common.Topics;
import com.quanla.sagademo.common.event.EventEnvelope;
import com.quanla.sagademo.common.event.EventTypes;
import com.quanla.sagademo.common.event.payload.*;
import com.quanla.sagademo.order.inbox.InboxGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka entry point for the orchestrator. Each handler is wrapped in a single
 * transaction so the inbox insert and the saga state change commit together.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaEventListener {

    private final ObjectMapper objectMapper;
    private final InboxGuard inboxGuard;
    private final SagaOrchestrator orchestrator;

    @KafkaListener(topics = Topics.INVENTORY_EVENTS, groupId = "order-service")
    @Transactional
    public void onInventoryEvent(String message) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
        if (!inboxGuard.markProcessed(envelope.messageId(), Topics.INVENTORY_EVENTS, envelope.type())) {
            return;
        }
        switch (envelope.type()) {
            case EventTypes.INVENTORY_RESERVED -> orchestrator.onInventoryReserved(
                    objectMapper.readValue(envelope.payload(), InventoryReservedEvent.class));
            case EventTypes.INVENTORY_RESERVATION_FAILED -> orchestrator.onInventoryReservationFailed(
                    objectMapper.readValue(envelope.payload(), InventoryReservationFailedEvent.class));
            case EventTypes.INVENTORY_RELEASED -> orchestrator.onInventoryReleased(
                    objectMapper.readValue(envelope.payload(), InventoryReleasedEvent.class));
            default -> log.debug("Ignoring inventory event type {}", envelope.type());
        }
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "order-service")
    @Transactional
    public void onPaymentEvent(String message) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
        if (!inboxGuard.markProcessed(envelope.messageId(), Topics.PAYMENT_EVENTS, envelope.type())) {
            return;
        }
        switch (envelope.type()) {
            case EventTypes.PAYMENT_COMPLETED -> orchestrator.onPaymentCompleted(
                    objectMapper.readValue(envelope.payload(), PaymentCompletedEvent.class));
            case EventTypes.PAYMENT_FAILED -> orchestrator.onPaymentFailed(
                    objectMapper.readValue(envelope.payload(), PaymentFailedEvent.class));
            default -> log.debug("Ignoring payment event type {}", envelope.type());
        }
    }
}