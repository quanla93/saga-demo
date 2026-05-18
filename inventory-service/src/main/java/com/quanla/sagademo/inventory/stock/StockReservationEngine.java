package com.quanla.sagademo.inventory.stock;

import com.quanla.sagademo.common.event.payload.OrderItemDto;

import java.util.List;
import java.util.UUID;

/**
 * Strategy abstraction over the "check + decrement available stock" step.
 * <p>
 * Two implementations:
 * <ul>
 *   <li>{@code DatabaseStockEngine} — pessimistic-lock SELECT FOR UPDATE on
 *       Postgres. Correct, deadlock-safe (lock order = product id), but
 *       serializes all reservations touching the same SKU. Good for low-volume.
 *   <li>{@code RedisStockEngine} — atomic Lua script with an idempotency set
 *       keyed by orderId. Sub-millisecond, scales to thousands of req/s per
 *       SKU. Used for flash-sale hot paths.
 * </ul>
 * Both engines treat the same orderId as idempotent: a retried command MUST
 * NOT decrement twice. The database engine relies on the existing
 * {@code reservations.order_id UNIQUE} constraint plus inbox dedup; the Redis
 * engine relies on an explicit reserved-orders set inside its Lua script.
 */
public interface StockReservationEngine {

    /**
     * Attempts to reserve the requested quantities for the given order.
     * <p>
     * If the orderId has already been reserved (saga retry, duplicate Kafka
     * delivery), the engine returns {@link ReservationOutcome.Success} without
     * a second decrement.
     */
    ReservationOutcome tryReserve(UUID orderId, List<OrderItemDto> items);

    /**
     * Restores the stock previously taken by {@link #tryReserve(UUID, List)}.
     * Idempotent: releasing the same orderId twice is a no-op (the engine
     * tracks which orders are still in the reserved set).
     */
    void release(UUID orderId, List<ReservationLine> items);

    /**
     * Seeds the engine's view of stock from the source of truth (Postgres).
     * Called on startup and exposed via admin endpoint for re-warming.
     */
    void warm(UUID productId, int stockAvailable);

    /**
     * Read-only peek used by the admin UI / debug endpoints.
     */
    long currentStock(UUID productId);

    record ReservationLine(UUID productId, int quantity) {}
}
