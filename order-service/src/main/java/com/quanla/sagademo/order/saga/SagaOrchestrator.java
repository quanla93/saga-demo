package com.quanla.sagademo.order.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanla.sagademo.common.Topics;
import com.quanla.sagademo.common.event.EventTypes;
import com.quanla.sagademo.common.event.payload.*;
import com.quanla.sagademo.order.domain.*;
import com.quanla.sagademo.order.outbox.OutboxRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Applies state transitions for one saga instance based on incoming events.
 * <p>
 * Each handler runs in its own transaction together with the inbox-guard insert,
 * so duplicate Kafka deliveries are naturally absorbed: if the inbox PK already
 * exists, the caller bails out and we never enter the handler.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SagaOrchestrator {

    private final SagaInstanceRepository sagaRepository;
    private final OrderRepository orderRepository;
    private final OutboxRecorder outbox;

    @Transactional
    public void onInventoryReserved(InventoryReservedEvent event) {
        SagaInstance saga = requireSagaForOrder(event.orderId());
        if (saga.getState() != SagaState.STARTED) {
            log.warn("Saga {} got InventoryReserved in unexpected state {} — ignoring",
                    saga.getId(), saga.getState());
            return;
        }
        saga.setReservationId(event.reservationId());
        saga.setState(SagaState.INVENTORY_RESERVED);
        sagaRepository.save(saga);

        Order order = orderRepository.findById(event.orderId()).orElseThrow();
        outbox.record(saga.getId(), "Order", order.getId(),
                Topics.PAYMENT_COMMANDS, order.getId().toString(),
                EventTypes.CHARGE_PAYMENT,
                new ChargePaymentCommand(order.getId(), order.getCustomerId(), order.getTotalAmount()));

        log.info("Saga {} → INVENTORY_RESERVED, requesting payment", saga.getId());
    }

    @Transactional
    public void onInventoryReservationFailed(InventoryReservationFailedEvent event) {
        SagaInstance saga = requireSagaForOrder(event.orderId());
        if (saga.getState() != SagaState.STARTED) {
            log.warn("Saga {} got InventoryReservationFailed in unexpected state {} — ignoring",
                    saga.getId(), saga.getState());
            return;
        }
        saga.setState(SagaState.FAILED);
        saga.setFailureReason(event.reason());
        sagaRepository.save(saga);

        Order order = orderRepository.findById(event.orderId()).orElseThrow();
        order.setStatus(OrderStatus.CANCELLED);
        order.setFailureReason(event.reason());
        orderRepository.save(order);

        log.info("Saga {} → FAILED (inventory): {}", saga.getId(), event.reason());
    }

    @Transactional
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        SagaInstance saga = requireSagaForOrder(event.orderId());
        if (saga.getState() != SagaState.INVENTORY_RESERVED) {
            log.warn("Saga {} got PaymentCompleted in unexpected state {} — ignoring",
                    saga.getId(), saga.getState());
            return;
        }
        saga.setPaymentId(event.paymentId());
        saga.setState(SagaState.COMPLETED);
        sagaRepository.save(saga);

        Order order = orderRepository.findById(event.orderId()).orElseThrow();
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);

        log.info("Saga {} → COMPLETED", saga.getId());
    }

    @Transactional
    public void onPaymentFailed(PaymentFailedEvent event) {
        SagaInstance saga = requireSagaForOrder(event.orderId());
        if (saga.getState() != SagaState.INVENTORY_RESERVED) {
            log.warn("Saga {} got PaymentFailed in unexpected state {} — ignoring",
                    saga.getId(), saga.getState());
            return;
        }
        // Payment failed → compensate inventory.
        saga.setState(SagaState.COMPENSATING_RELEASE_INVENTORY);
        saga.setFailureReason(event.reason());
        sagaRepository.save(saga);

        outbox.record(saga.getId(), "Order", event.orderId(),
                Topics.INVENTORY_COMMANDS, event.orderId().toString(),
                EventTypes.RELEASE_INVENTORY,
                new ReleaseInventoryCommand(event.orderId(), saga.getReservationId()));

        log.info("Saga {} → COMPENSATING_RELEASE_INVENTORY: {}", saga.getId(), event.reason());
    }

    @Transactional
    public void onInventoryReleased(InventoryReleasedEvent event) {
        SagaInstance saga = requireSagaForOrder(event.orderId());
        if (saga.getState() != SagaState.COMPENSATING_RELEASE_INVENTORY) {
            log.warn("Saga {} got InventoryReleased in unexpected state {} — ignoring",
                    saga.getId(), saga.getState());
            return;
        }
        saga.setState(SagaState.FAILED);
        sagaRepository.save(saga);

        Order order = orderRepository.findById(event.orderId()).orElseThrow();
        order.setStatus(OrderStatus.CANCELLED);
        order.setFailureReason(saga.getFailureReason());
        orderRepository.save(order);

        log.info("Saga {} → FAILED (compensated)", saga.getId());
    }

    private SagaInstance requireSagaForOrder(UUID orderId) {
        return sagaRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "No saga instance for order " + orderId));
    }
}