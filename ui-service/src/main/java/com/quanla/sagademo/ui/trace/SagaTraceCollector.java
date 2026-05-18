package com.quanla.sagademo.ui.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanla.sagademo.common.Topics;
import com.quanla.sagademo.common.event.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Observer-only consumer. Sits in its own consumer group so it receives every
 * message that flows through the four saga topics WITHOUT competing with the
 * order/payment/inventory services. Pulls the orderId out of the envelope's
 * payload (every saga payload includes one) and records a timeline entry.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaTraceCollector {

    private static final int PAYLOAD_PREVIEW_LIMIT = 240;

    private final ObjectMapper objectMapper;
    private final SagaTraceStore store;

    @KafkaListener(topics = {
            Topics.INVENTORY_COMMANDS,
            Topics.INVENTORY_EVENTS,
            Topics.PAYMENT_COMMANDS,
            Topics.PAYMENT_EVENTS
    })
    public void onMessage(@Header(KafkaHeaders.RECEIVED_TOPIC) String topic, String raw) {
        try {
            EventEnvelope envelope = objectMapper.readValue(raw, EventEnvelope.class);
            UUID orderId = extractOrderId(envelope.payload());
            if (orderId == null) {
                log.debug("Ignoring message without orderId on {} (type={})", topic, envelope.type());
                return;
            }
            store.record(new SagaTraceEntry(
                    envelope.occurredAt(),
                    topic,
                    envelope.type(),
                    envelope.messageId(),
                    envelope.sagaId(),
                    orderId,
                    truncate(envelope.payload())));
        } catch (Exception e) {
            // Bad data on the trace topic shouldn't kill the listener.
            log.warn("Skipping unparseable trace message on {}: {}", topic, e.getMessage());
        }
    }

    private UUID extractOrderId(String payloadJson) throws Exception {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        JsonNode node = objectMapper.readTree(payloadJson);
        JsonNode orderIdNode = node.get("orderId");
        return orderIdNode == null ? null : UUID.fromString(orderIdNode.asText());
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= PAYLOAD_PREVIEW_LIMIT
                ? s
                : s.substring(0, PAYLOAD_PREVIEW_LIMIT) + "...(truncated)";
    }
}
