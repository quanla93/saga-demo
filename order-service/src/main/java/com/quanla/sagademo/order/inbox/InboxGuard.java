package com.quanla.sagademo.order.inbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Records that a Kafka message has been processed.
 * <p>
 * Call {@link #markProcessed} INSIDE the same DB transaction that applies the
 * business effect. If the transaction commits, the inbox row commits with it;
 * if the inbox insert violates the PK, we know we've seen this message before
 * and the caller should treat the event as a duplicate (return without effect).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InboxGuard {

    private final InboxMessageRepository repository;

    /**
     * @return true if this is the first time we've seen the messageId,
     *         false if it's a duplicate (already processed).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID messageId, String sourceTopic, String eventType) {
        if (repository.existsById(messageId)) {
            log.debug("Duplicate message {} on topic {} — skipping", messageId, sourceTopic);
            return false;
        }
        try {
            repository.saveAndFlush(InboxMessage.builder()
                    .messageId(messageId)
                    .sourceTopic(sourceTopic)
                    .eventType(eventType)
                    .processedAt(Instant.now())
                    .build());
            return true;
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent consumer — treat as duplicate.
            log.debug("Race on inbox insert for {} — treating as duplicate", messageId);
            return false;
        }
    }
}