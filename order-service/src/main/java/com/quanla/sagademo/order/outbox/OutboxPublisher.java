package com.quanla.sagademo.order.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Polls the outbox table for unpublished events and publishes them to Kafka.
 * <p>
 * After a successful send we mark the row as published in the same transaction.
 * At-least-once is guaranteed: if publish succeeds but the mark-as-published commit
 * fails, the next poll will republish — consumers dedup via the Inbox table.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelayString = "${saga.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.findUnpublished(
                PageRequest.of(0, 50));
        if (batch.isEmpty()) return;

        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                        .get();
                event.setPublishedAt(Instant.now());
                log.debug("Published outbox event id={} type={} topic={}",
                        event.getId(), event.getEventType(), event.getTopic());
            } catch (Exception e) {
                log.warn("Failed to publish outbox event id={} — will retry next poll",
                        event.getId(), e);
                // Stop the batch on first failure to preserve ordering per key.
                break;
            }
        }
    }
}