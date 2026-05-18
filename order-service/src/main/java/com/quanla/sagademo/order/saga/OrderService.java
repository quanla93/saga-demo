package com.quanla.sagademo.order.saga;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanla.sagademo.common.Topics;
import com.quanla.sagademo.common.event.EventTypes;
import com.quanla.sagademo.common.event.payload.OrderItemDto;
import com.quanla.sagademo.common.event.payload.ReserveInventoryCommand;
import com.quanla.sagademo.order.api.dto.CreateOrderRequest;
import com.quanla.sagademo.order.domain.*;
import com.quanla.sagademo.order.outbox.OutboxRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates a new order and kicks off the saga.
 * <p>
 * The atomic unit is: insert Order + insert SagaInstance + insert outbox row
 * (ReserveInventoryCommand). All three commit together — the outbox publisher
 * picks the command up afterwards and pushes it onto Kafka.
 * <p>
 * Idempotency: clients pass an Idempotency-Key header. If we've seen it, we
 * return the existing order id without creating a duplicate or re-starting the saga.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaRepository;
    private final IdempotencyKeyRepository idempotencyRepository;
    private final OutboxRecorder outbox;

    @Transactional
    public Order createOrder(CreateOrderRequest request, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyKey> existing = idempotencyRepository.findById(idempotencyKey);
            if (existing.isPresent()) {
                UUID existingOrderId = existing.get().getOrderId();
                log.info("Idempotency hit key={} → returning existing order {}",
                        idempotencyKey, existingOrderId);
                return orderRepository.findById(existingOrderId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotency key references missing order " + existingOrderId));
            }
        }

        UUID orderId = UUID.randomUUID();
        UUID sagaId = UUID.randomUUID();

        BigDecimal total = request.items().stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = Order.builder()
                .id(orderId)
                .customerId(request.customerId())
                .totalAmount(total)
                .status(OrderStatus.PENDING)
                .build();

        request.items().forEach(item -> order.getItems().add(OrderItem.builder()
                .id(UUID.randomUUID())
                .order(order)
                .productId(item.productId())
                .quantity(item.quantity())
                .unitPrice(item.unitPrice())
                .build()));

        orderRepository.save(order);

        SagaInstance saga = SagaInstance.builder()
                .id(sagaId)
                .orderId(orderId)
                .state(SagaState.STARTED)
                .build();
        sagaRepository.save(saga);

        // First step: reserve inventory. We emit the command via outbox so the
        // Kafka publish happens in a separate transaction after THIS one commits.
        List<OrderItemDto> itemDtos = request.items().stream()
                .map(i -> new OrderItemDto(i.productId(), i.quantity(), i.unitPrice()))
                .toList();
        outbox.record(sagaId, "Order", orderId,
                Topics.INVENTORY_COMMANDS, orderId.toString(),
                EventTypes.RESERVE_INVENTORY,
                new ReserveInventoryCommand(orderId, itemDtos));

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyRepository.save(IdempotencyKey.builder()
                    .key(idempotencyKey)
                    .orderId(orderId)
                    .createdAt(Instant.now())
                    .build());
        }

        log.info("Created order {} with saga {} — starting orchestration", orderId, sagaId);
        return order;
    }
}