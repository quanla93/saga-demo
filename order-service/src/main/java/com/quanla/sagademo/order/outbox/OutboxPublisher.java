package com.quanla.sagademo.order.outbox;

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
 * <p>
 * After a successful send we mark the row as published in the same transaction.
 * At-least-once is guaranteed: if publish succeeds but the mark-as-published commit
 * fails, the next poll will republish — consumers dedup via the Inbox table.
 * <p>
 * Failure handling:
 * <ul>
 *   <li>{@code send(...).get(sendTimeoutMs)} bounds the wait so a hung broker can
 *       never block the publisher thread indefinitely.</li>
 *   <li>Every failure increments {@code attempts} and stores the latest error,
 *       so operators can alert on rows whose attempt count keeps climbing.</li>
 *   <li>After {@code maxAttempts}, the row is moved to the parking lot by setting
 *       {@code parked_at}. The polling index excludes parked rows so a poison
 *       payload no longer blocks healthy events forever.</li>
 *   <li>Transient failures still {@code break} the batch to preserve per-key
 *       ordering; only terminal parking advances to the next event in the batch.</li>
 * </ul>
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
        List<OutboxEvent> batch = repository.findUnpublished(
                PageRequest.of(0, 50));
        if (batch.isEmpty()) return;

        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                event.setPublishedAt(Instant.now());
                log.debug("Published outbox event id={} type={} topic={}",
                        event.getId(), event.getEventType(), event.getTopic());
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
