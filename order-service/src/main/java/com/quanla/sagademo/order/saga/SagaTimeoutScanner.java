package com.quanla.sagademo.order.saga;

import com.quanla.sagademo.common.Topics;
import com.quanla.sagademo.common.event.EventTypes;
import com.quanla.sagademo.common.event.payload.ReleaseInventoryCommand;
import com.quanla.sagademo.order.domain.*;
import com.quanla.sagademo.order.outbox.OutboxRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scheduled detector for stuck sagas.
 * <p>
 * A saga can stall for several reasons that retry+DLT does NOT cover:
 * <ul>
 *   <li>A participant crashed before processing the command and the message
 *       eventually landed in the DLT — order-service never received a reply
 *       and would otherwise wait forever
 *   <li>An event from a participant was lost (rare, but possible during
 *       broker-side issues with poor producer config — defence in depth)
 *   <li>A bug or operator action sat the saga in a transient state with no
 *       further input
 * </ul>
 * Every {@code saga.timeout-scan-seconds} the scanner finds non-terminal sagas
 * whose {@code updated_at} is older than {@code saga.timeout-seconds} and
 * forces them forward to a safe terminal state — compensating where there's
 * upstream state to undo, otherwise just marking FAILED.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SagaTimeoutScanner {

    private final SagaInstanceRepository sagaRepository;
    private final OrderRepository orderRepository;
    private final OutboxRecorder outbox;

    @Value("${saga.timeout-seconds:120}")
    private long timeoutSeconds;

    @Scheduled(fixedDelayString = "${saga.timeout-scan-seconds:30}000",
               initialDelay = 10_000L)
    @Transactional
    public void scan() {
        Instant threshold = Instant.now().minus(Duration.ofSeconds(timeoutSeconds));
        List<SagaInstance> stuck = sagaRepository.findStuckSince(threshold);
        if (stuck.isEmpty()) return;
        log.warn("Saga timeout scan found {} stuck sagas older than {}s", stuck.size(), timeoutSeconds);
        for (SagaInstance saga : stuck) {
            try {
                handleStuck(saga);
            } catch (Exception e) {
                // Don't let one bad saga block recovery of the others.
                log.error("Failed to recover stuck saga {} ({}): {}",
                        saga.getId(), saga.getState(), e.getMessage(), e);
            }
        }
    }

    private void handleStuck(SagaInstance saga) {
        String reason = "Saga timed out after " + timeoutSeconds + "s in state " + saga.getState();
        switch (saga.getState()) {
            case STARTED -> {
                // No upstream side effect to compensate — inventory never
                // confirmed reservation. Safe to just fail.
                failOrder(saga, reason);
                log.warn("Saga {} stuck in STARTED → FAILED ({})", saga.getId(), reason);
            }
            case INVENTORY_RESERVED -> {
                // Inventory was decremented but payment never replied. Emit
                // compensating release and let the existing flow finish.
                saga.setState(SagaState.COMPENSATING_RELEASE_INVENTORY);
                saga.setFailureReason(reason);
                sagaRepository.save(saga);
                outbox.record(saga.getId(), "Order", saga.getOrderId(),
                        Topics.INVENTORY_COMMANDS, saga.getOrderId().toString(),
                        EventTypes.RELEASE_INVENTORY,
                        new ReleaseInventoryCommand(saga.getOrderId(), saga.getReservationId()));
                log.warn("Saga {} stuck in INVENTORY_RESERVED → COMPENSATING_RELEASE_INVENTORY ({})",
                        saga.getId(), reason);
            }
            case COMPENSATING_RELEASE_INVENTORY, COMPENSATING_REFUND_PAYMENT -> {
                // Already compensating but the compensation itself stalled.
                // Mark the order cancelled and the saga FAILED; an operator
                // needs to verify the participant state by hand.
                failOrder(saga, reason + " (compensation stalled — needs operator review)");
                log.error("Saga {} stuck in compensation state {} → FAILED, MANUAL REVIEW REQUIRED",
                        saga.getId(), saga.getState());
            }
            default -> log.debug("Saga {} in state {} — no timeout action", saga.getId(), saga.getState());
        }
    }

    private void failOrder(SagaInstance saga, String reason) {
        saga.setState(SagaState.FAILED);
        saga.setFailureReason(reason);
        sagaRepository.save(saga);
        orderRepository.findById(saga.getOrderId()).ifPresent(o -> {
            o.setStatus(OrderStatus.CANCELLED);
            o.setFailureReason(reason);
            orderRepository.save(o);
        });
    }
}
