package com.quanla.sagademo.inventory.stock;

import java.util.UUID;

/**
 * Result of asking an engine to reserve stock for an order.
 * <p>
 * Sealed so {@link com.quanla.sagademo.inventory.service.InventoryService}
 * can exhaustively pattern-match on it without forgetting a case.
 */
public sealed interface ReservationOutcome {

    boolean isSuccess();

    String reason();

    record Success() implements ReservationOutcome {
        public boolean isSuccess() { return true; }
        public String reason() { return null; }
    }

    record InsufficientStock(UUID productId, long available, int requested) implements ReservationOutcome {
        public boolean isSuccess() { return false; }
        public String reason() {
            return "Insufficient stock for product " + productId
                    + " (available=" + available + ", requested=" + requested + ")";
        }
    }

    record UnknownProduct(UUID productId) implements ReservationOutcome {
        public boolean isSuccess() { return false; }
        public String reason() { return "Unknown product " + productId; }
    }
}
