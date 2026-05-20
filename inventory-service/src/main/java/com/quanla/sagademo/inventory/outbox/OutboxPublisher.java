package com.quanla.sagademo.inventory.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polls the outbox table for unpublished events and publishes them to Kafka.
 * See {@code order-service} OutboxPublisher for the timeout/retry/parking
 * contract — the three services share the same behaviour.
 */
@Component
@Slf4j
public class OutboxPublisher {

    private static final int MAX_ERROR_LENGTH = 2_000;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final long sendTimeoutMs;
    private final int maxAttempts;

    public OutboxPublisher(OutboxEventRepository repository,
                           KafkaTemplate<String, String> kafkaTemplate,
                           @Value("${saga.outbox.send-timeout-ms:5000}") long sendTimeoutMs,
                           @Value("${saga.outbox.max-attempts:10}") int maxAttempts) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeoutMs = sendTimeoutMs;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${saga.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.findUnpublished(PageRequest.of(0, 50));
        if (batch.isEmpty()) return;
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                event.setPublishedAt(Instant.now());
                log.debug("Published outbox event id={} type={}", event.getId(), event.getEventType());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordFailure(event, e);
                break;
            } catch (TimeoutException | RuntimeException | java.util.concurrent.ExecutionException e) {
                boolean parked = recordFailure(event, e);
                if (!parked) {
                    break;
                }
            }
        }
    }

    private boolean recordFailure(OutboxEvent event, Throwable error) {
        event.setAttempts(event.getAttempts() + 1);
        event.setLastError(truncate(error.getClass().getName() + ": " + error.getMessage()));
        if (event.getAttempts() >= maxAttempts) {
            event.setParkedAt(Instant.now());
            log.error("Parked outbox event id={} type={} after {} attempts — operator action required",
                    event.getId(), event.getEventType(), event.getAttempts(), error);
            return true;
        }
        log.warn("Failed to publish outbox event id={} type={} attempt={}/{} — will retry next poll",
                event.getId(), event.getEventType(), event.getAttempts(), maxAttempts, error);
        return false;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
