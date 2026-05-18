package com.quanla.sagademo.order.api.dto;

import com.quanla.sagademo.order.domain.Order;
import com.quanla.sagademo.order.domain.SagaInstance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        UUID customerId,
        BigDecimal totalAmount,
        String status,
        String sagaState,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        List<OrderItemResponse> items
) {
    public static OrderResponse from(Order order, SagaInstance saga) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(OrderItemResponse::from)
                .toList();
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getTotalAmount(),
                order.getStatus().name(),
                saga == null ? null : saga.getState().name(),
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                items
        );
    }
}