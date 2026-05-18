package com.quanla.sagademo.ui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderView(
        UUID orderId,
        UUID customerId,
        BigDecimal totalAmount,
        String status,
        String sagaState,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        List<OrderItemView> items
) {
    /**
     * Returns a friendly description of where the saga is for the human-facing UI.
     */
    public String currentStep() {
        if (sagaState == null) return "PENDING";
        return switch (sagaState) {
            case "STARTED" -> "Reserving inventory…";
            case "INVENTORY_RESERVED" -> "Charging payment…";
            case "PAYMENT_COMPLETED", "COMPLETED" -> "Order confirmed";
            case "COMPENSATING_RELEASE_INVENTORY" -> "Payment failed — releasing inventory…";
            case "COMPENSATING_REFUND_PAYMENT" -> "Compensating — refunding payment…";
            case "FAILED" -> "Order cancelled" + (failureReason == null ? "" : ": " + failureReason);
            default -> sagaState;
        };
    }

    public boolean isTerminal() {
        return "COMPLETED".equals(sagaState) || "FAILED".equals(sagaState);
    }
}
