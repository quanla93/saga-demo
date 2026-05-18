package com.quanla.sagademo.inventory.service;

import com.quanla.sagademo.common.Topics;
import com.quanla.sagademo.common.event.EventTypes;
import com.quanla.sagademo.common.event.payload.*;
import com.quanla.sagademo.inventory.domain.*;
import com.quanla.sagademo.inventory.outbox.OutboxRecorder;
import com.quanla.sagademo.inventory.stock.ReservationOutcome;
import com.quanla.sagademo.inventory.stock.StockReservationEngine;
import com.quanla.sagademo.inventory.stock.StockReservationEngine.ReservationLine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reserves and releases stock for the saga.
 * <p>
 * The "check + decrement available stock" step is delegated to a
 * {@link StockReservationEngine} (routed at runtime between Database and Redis
 * implementations). Persistence of the {@link Reservation} entity and emission
 * of saga events stays here.
 * <p>
 * Two layers of idempotency:
 * <ul>
 *   <li>{@link Reservation#getOrderId()} is UNIQUE — a retried command finds
 *       the existing reservation, re-emits the same outcome event, and skips
 *       the engine call entirely.
 *   <li>If a retry slips past the above (e.g. the first attempt's TX rolled
 *       back before inserting Reservation but AFTER Redis already decremented),
 *       the engine itself dedups by orderId — the Redis Lua script's
 *       {@code reserved-orders} SET ensures we never double-decrement.
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final ReservationRepository reservationRepository;
    private final StockReservationEngine engine;
    private final OutboxRecorder outbox;

    @Transactional
    public void reserve(UUID sagaId, ReserveInventoryCommand command) {
        Optional<Reservation> existing = reservationRepository.findByOrderId(command.orderId());
        if (existing.isPresent()) {
            Reservation r = existing.get();
            log.info("Reservation for order {} already exists (status={}) — re-emitting outcome",
                    command.orderId(), r.getStatus());
            if (r.getStatus() == ReservationStatus.RESERVED) {
                outbox.record(sagaId, "Reservation", r.getId(),
                        Topics.INVENTORY_EVENTS, command.orderId().toString(),
                        EventTypes.INVENTORY_RESERVED,
                        new InventoryReservedEvent(command.orderId(), r.getId()));
            }
            return;
        }

        ReservationOutcome outcome = engine.tryReserve(command.orderId(), command.items());

        if (!outcome.isSuccess()) {
            emitFailure(sagaId, command.orderId(), outcome.reason());
            return;
        }

        Reservation reservation = Reservation.builder()
                .id(UUID.randomUUID())
                .orderId(command.orderId())
                .status(ReservationStatus.RESERVED)
                .build();
        for (OrderItemDto item : command.items()) {
            reservation.getItems().add(ReservationItem.builder()
                    .id(UUID.randomUUID())
                    .reservation(reservation)
                    .productId(item.productId())
                    .quantity(item.quantity())
                    .build());
        }
        reservationRepository.save(reservation);

        outbox.record(sagaId, "Reservation", reservation.getId(),
                Topics.INVENTORY_EVENTS, command.orderId().toString(),
                EventTypes.INVENTORY_RESERVED,
                new InventoryReservedEvent(command.orderId(), reservation.getId()));
        log.info("Reserved inventory for order {} (reservation {})",
                command.orderId(), reservation.getId());
    }

    @Transactional
    public void release(UUID sagaId, ReleaseInventoryCommand command) {
        Reservation reservation = reservationRepository.findById(command.reservationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown reservation " + command.reservationId()));
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            log.info("Reservation {} already released — re-emitting event", reservation.getId());
        } else {
            List<ReservationLine> lines = reservation.getItems().stream()
                    .map(i -> new ReservationLine(i.getProductId(), i.getQuantity()))
                    .toList();
            engine.release(reservation.getOrderId(), lines);
            reservation.setStatus(ReservationStatus.RELEASED);
            reservationRepository.save(reservation);
        }
        outbox.record(sagaId, "Reservation", reservation.getId(),
                Topics.INVENTORY_EVENTS, command.orderId().toString(),
                EventTypes.INVENTORY_RELEASED,
                new InventoryReleasedEvent(command.orderId(), reservation.getId()));
        log.info("Released inventory for order {} (reservation {})",
                command.orderId(), reservation.getId());
    }

    private void emitFailure(UUID sagaId, UUID orderId, String reason) {
        outbox.record(sagaId, "Reservation", orderId,
                Topics.INVENTORY_EVENTS, orderId.toString(),
                EventTypes.INVENTORY_RESERVATION_FAILED,
                new InventoryReservationFailedEvent(orderId, reason));
        log.info("Inventory reservation failed for order {}: {}", orderId, reason);
    }
}
