package com.quanla.sagademo.inventory.inbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class InboxGuard {

    private final InboxMessageRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(UUID messageId, String sourceTopic, String eventType) {
        if (repository.existsById(messageId)) {
            log.debug("Duplicate message {} on {} — skipping", messageId, sourceTopic);
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
            return false;
        }
    }
}
