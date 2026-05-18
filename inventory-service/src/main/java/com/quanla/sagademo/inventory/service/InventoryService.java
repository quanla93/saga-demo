package com.quanla.sagademo.inventory.service;

import com.quanla.sagademo.common.Topics;
import com.quanla.sagademo.common.event.EventTypes;
import com.quanla.sagademo.common.event.payload.*;
import com.quanla.sagademo.inventory.domain.*;
import com.quanla.sagademo.inventory.outbox.OutboxRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Reserves and releases stock for the saga.
 * <p>
 * Reservation is the all-or-nothing step that may force the saga to compensate
 * payment. Release is the compensating action triggered when downstream
 * (payment) fails after we've already moved stock from available → reserved.
 * <p>
 * Concurrency: we take a pessimistic write lock on every Product touched by the
 * order, sorted by id, so two concurrent reservations cannot double-spend the
 * same SKU and the lock order is consistent (no deadlock).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final ProductRepository productRepository;
    private final ReservationRepository reservationRepository;
    private final OutboxRecorder outbox;

    @Transactional
    public void reserve(UUID sagaId, ReserveInventoryCommand command) {
        Optional<Reservation> existing = reservationRepository.findByOrderId(command.orderId());
        if (existing.isPresent()) {
            log.info("Reservation for order {} already exists — re-emitting outcome", command.orderId());
            Reservation r = existing.get();
            if (r.getStatus() == ReservationStatus.RESERVED) {
                outbox.record(sagaId, "Reservation", r.getId(),
                        Topics.INVENTORY_EVENTS, command.orderId().toString(),
                        EventTypes.INVENTORY_RESERVED,
                        new InventoryReservedEvent(command.orderId(), r.getId()));
            }
            return;
        }

        List<UUID> productIds = command.items().stream()
                .map(OrderItemDto::productId)
                .distinct()
                .sorted()
                .toList();
        Map<UUID, Product> productsById = productRepository
                .lockAllByIdOrderedById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        for (OrderItemDto item : command.items()) {
            Product product = productsById.get(item.productId());
            if (product == null) {
                emitFailure(sagaId, command.orderId(), "Unknown product " + item.productId());
                return;
            }
            if (product.getStockAvailable() < item.quantity()) {
                emitFailure(sagaId, command.orderId(),
                        "Insufficient stock for SKU " + product.getSku()
                                + " (have " + product.getStockAvailable()
                                + ", need " + item.quantity() + ")");
                return;
            }
        }

        Reservation reservation = Reservation.builder()
                .id(UUID.randomUUID())
                .orderId(command.orderId())
                .status(ReservationStatus.RESERVED)
                .build();

        for (OrderItemDto item : command.items()) {
            Product product = productsById.get(item.productId());
            product.setStockAvailable(product.getStockAvailable() - item.quantity());
            product.setStockReserved(product.getStockReserved() + item.quantity());

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
        log.info("Reserved inventory for order {} (reservation {})", command.orderId(), reservation.getId());
    }

    @Transactional
    public void release(UUID sagaId, ReleaseInventoryCommand command) {
        Reservation reservation = reservationRepository.findById(command.reservationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown reservation " + command.reservationId()));
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            log.info("Reservation {} already released — re-emitting event", reservation.getId());
        } else {
            List<UUID> productIds = reservation.getItems().stream()
                    .map(ReservationItem::getProductId)
                    .distinct()
                    .sorted()
                    .toList();
            Map<UUID, Product> productsById = productRepository
                    .lockAllByIdOrderedById(productIds).stream()
                    .collect(Collectors.toMap(Product::getId, p -> p));

            for (ReservationItem item : reservation.getItems()) {
                Product product = productsById.get(item.getProductId());
                product.setStockReserved(product.getStockReserved() - item.getQuantity());
                product.setStockAvailable(product.getStockAvailable() + item.getQuantity());
            }
            reservation.setStatus(ReservationStatus.RELEASED);
            reservationRepository.save(reservation);
        }
        outbox.record(sagaId, "Reservation", reservation.getId(),
                Topics.INVENTORY_EVENTS, command.orderId().toString(),
                EventTypes.INVENTORY_RELEASED,
                new InventoryReleasedEvent(command.orderId(), reservation.getId()));
        log.info("Released inventory for order {} (reservation {})", command.orderId(), reservation.getId());
    }

    private void emitFailure(UUID sagaId, UUID orderId, String reason) {
        outbox.record(sagaId, "Reservation", orderId,
                Topics.INVENTORY_EVENTS, orderId.toString(),
                EventTypes.INVENTORY_RESERVATION_FAILED,
                new InventoryReservationFailedEvent(orderId, reason));
        log.info("Inventory reservation failed for order {}: {}", orderId, reason);
    }
}
