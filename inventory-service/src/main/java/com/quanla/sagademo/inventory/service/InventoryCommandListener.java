package com.quanla.sagademo.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanla.sagademo.common.Topics;
import com.quanla.sagademo.common.event.EventEnvelope;
import com.quanla.sagademo.common.event.EventTypes;
import com.quanla.sagademo.common.event.payload.ReleaseInventoryCommand;
import com.quanla.sagademo.common.event.payload.ReserveInventoryCommand;
import com.quanla.sagademo.inventory.inbox.InboxGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryCommandListener {

    private final ObjectMapper objectMapper;
    private final InboxGuard inboxGuard;
    private final InventoryService inventoryService;

    @KafkaListener(topics = Topics.INVENTORY_COMMANDS, groupId = "inventory-service")
    @Transactional
    public void onCommand(String message) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
        if (!inboxGuard.markProcessed(envelope.messageId(), Topics.INVENTORY_COMMANDS, envelope.type())) {
            return;
        }
        switch (envelope.type()) {
            case EventTypes.RESERVE_INVENTORY -> inventoryService.reserve(envelope.sagaId(),
                    objectMapper.readValue(envelope.payload(), ReserveInventoryCommand.class));
            case EventTypes.RELEASE_INVENTORY -> inventoryService.release(envelope.sagaId(),
                    objectMapper.readValue(envelope.payload(), ReleaseInventoryCommand.class));
            default -> log.debug("Ignoring inventory command type {}", envelope.type());
        }
    }
}
