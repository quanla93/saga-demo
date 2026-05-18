package com.quanla.sagademo.payment.api.dto;

import com.quanla.sagademo.payment.domain.Payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        UUID customerId,
        BigDecimal amount,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(
                p.getId(), p.getOrderId(), p.getCustomerId(),
                p.getAmount(), p.getStatus().name(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
