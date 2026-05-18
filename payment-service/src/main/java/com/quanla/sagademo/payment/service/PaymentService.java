package com.quanla.sagademo.payment.service;

import com.quanla.sagademo.common.Topics;
import com.quanla.sagademo.common.event.EventTypes;
import com.quanla.sagademo.common.event.payload.*;
import com.quanla.sagademo.payment.domain.Payment;
import com.quanla.sagademo.payment.domain.PaymentRepository;
import com.quanla.sagademo.payment.domain.PaymentStatus;
import com.quanla.sagademo.payment.outbox.OutboxRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Business logic for the payment participant.
 * <p>
 * The "charge" simulation rejects any amount above {@code saga.payment.fail-above-amount}
 * so the demo can exercise the compensating path without needing a real PSP.
 * <p>
 * Each method writes the Payment row and an outbox row in the SAME transaction
 * (atomic effect from a downstream observer's perspective).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository repository;
    private final OutboxRecorder outbox;

    @Value("${saga.payment.fail-above-amount:5000}")
    private BigDecimal failAboveAmount;

    @Transactional
    public void charge(UUID sagaId, ChargePaymentCommand command) {
        if (repository.findByOrderId(command.orderId()).isPresent()) {
            log.info("Payment for order {} already exists — emitting event from existing state", command.orderId());
            Payment existing = repository.findByOrderId(command.orderId()).get();
            emitOutcome(sagaId, existing);
            return;
        }

        boolean approved = command.amount().compareTo(failAboveAmount) <= 0;

        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .orderId(command.orderId())
                .customerId(command.customerId())
                .amount(command.amount())
                .status(approved ? PaymentStatus.COMPLETED : PaymentStatus.FAILED)
                .build();
        repository.save(payment);

        if (approved) {
            outbox.record(sagaId, "Payment", payment.getId(),
                    Topics.PAYMENT_EVENTS, command.orderId().toString(),
                    EventTypes.PAYMENT_COMPLETED,
                    new PaymentCompletedEvent(command.orderId(), payment.getId(), command.amount()));
            log.info("Charged payment {} for order {} amount={}",
                    payment.getId(), command.orderId(), command.amount());
        } else {
            String reason = "Amount " + command.amount() + " exceeds fail threshold " + failAboveAmount;
            outbox.record(sagaId, "Payment", payment.getId(),
                    Topics.PAYMENT_EVENTS, command.orderId().toString(),
                    EventTypes.PAYMENT_FAILED,
                    new PaymentFailedEvent(command.orderId(), reason));
            log.info("Rejected payment for order {}: {}", command.orderId(), reason);
        }
    }

    @Transactional
    public void refund(UUID sagaId, RefundPaymentCommand command) {
        Payment payment = repository.findById(command.paymentId())
                .orElseThrow(() -> new IllegalStateException("Unknown payment " + command.paymentId()));
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment {} already refunded — re-emitting event", payment.getId());
        } else {
            payment.setStatus(PaymentStatus.REFUNDED);
            repository.save(payment);
        }
        outbox.record(sagaId, "Payment", payment.getId(),
                Topics.PAYMENT_EVENTS, command.orderId().toString(),
                EventTypes.PAYMENT_REFUNDED,
                new PaymentRefundedEvent(command.orderId(), payment.getId()));
    }

    private void emitOutcome(UUID sagaId, Payment payment) {
        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            outbox.record(sagaId, "Payment", payment.getId(),
                    Topics.PAYMENT_EVENTS, payment.getOrderId().toString(),
                    EventTypes.PAYMENT_COMPLETED,
                    new PaymentCompletedEvent(payment.getOrderId(), payment.getId(), payment.getAmount()));
        } else if (payment.getStatus() == PaymentStatus.FAILED) {
            outbox.record(sagaId, "Payment", payment.getId(),
                    Topics.PAYMENT_EVENTS, payment.getOrderId().toString(),
                    EventTypes.PAYMENT_FAILED,
                    new PaymentFailedEvent(payment.getOrderId(), "Replayed failure"));
        }
    }
}