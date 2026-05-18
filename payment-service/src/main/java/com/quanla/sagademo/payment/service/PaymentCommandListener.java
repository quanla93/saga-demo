package com.quanla.sagademo.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanla.sagademo.common.Topics;
import com.quanla.sagademo.common.event.EventEnvelope;
import com.quanla.sagademo.common.event.EventTypes;
import com.quanla.sagademo.common.event.payload.ChargePaymentCommand;
import com.quanla.sagademo.common.event.payload.RefundPaymentCommand;
import com.quanla.sagademo.payment.inbox.InboxGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCommandListener {

    private final ObjectMapper objectMapper;
    private final InboxGuard inboxGuard;
    private final PaymentService paymentService;

    @KafkaListener(topics = Topics.PAYMENT_COMMANDS, groupId = "payment-service")
    @Transactional
    public void onCommand(String message) throws Exception {
        EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
        if (!inboxGuard.markProcessed(envelope.messageId(), Topics.PAYMENT_COMMANDS, envelope.type())) {
            return;
        }
        switch (envelope.type()) {
            case EventTypes.CHARGE_PAYMENT -> paymentService.charge(envelope.sagaId(),
                    objectMapper.readValue(envelope.payload(), ChargePaymentCommand.class));
            case EventTypes.REFUND_PAYMENT -> paymentService.refund(envelope.sagaId(),
                    objectMapper.readValue(envelope.payload(), RefundPaymentCommand.class));
            default -> log.debug("Ignoring payment command type {}", envelope.type());
        }
    }
}