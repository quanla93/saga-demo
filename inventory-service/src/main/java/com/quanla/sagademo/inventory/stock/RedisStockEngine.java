package com.quanla.sagademo.inventory.stock;

import com.quanla.sagademo.common.event.payload.OrderItemDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Atomic-Lua-script implementation. Active when
 * {@code saga.inventory.stock-source=REDIS} (default).
 * <p>
 * Stock is held in Redis under {@code stock:{productId}} integer keys. A
 * companion Set {@code reserved-orders} tracks orderIds we've already
 * decremented for, so a Kafka retry of the same {@code ReserveInventory}
 * command does not double-decrement.
 * <p>
 * Postgres still owns the long-term truth (Reservation rows, Product audit
 * counters). The flow is: Redis decrement first (the throttle), then Postgres
 * Reservation insert + outbox emission inside one DB transaction. If the
 * Postgres step fails, the Kafka listener throws and {@link KafkaErrorHandlerConfig}
 * retries — the Lua script's idempotency set absorbs the duplicate, so we
 * don't over-decrement on retry.
 */
@Component("redisStockEngine")
@RequiredArgsConstructor
@Slf4j
public class RedisStockEngine implements StockReservationEngine {

    public static final String RESERVED_ORDERS_KEY = "reserved-orders";

    private final StringRedisTemplate redis;
    private final RedisScript<List> reserveScript;
    private final RedisScript<Long> releaseScript;

    /**
     * Lua return value contract:
     *   {1}                     → already reserved (duplicate-safe success)
     *   {2}                     → fresh reservation success
     *   {0, failedIndex, available} → insufficient stock at item index (1-based)
     */
    @Override
    public ReservationOutcome tryReserve(UUID orderId, List<OrderItemDto> items) {
        List<String> keys = new ArrayList<>(items.size() + 1);
        keys.add(RESERVED_ORDERS_KEY);
        items.forEach(i -> keys.add(stockKey(i.productId())));

        Object[] args = new Object[items.size() + 1];
        args[0] = orderId.toString();
        for (int i = 0; i < items.size(); i++) {
            args[i + 1] = String.valueOf(items.get(i).quantity());
        }

        @SuppressWarnings("unchecked")
        List<Long> result = redis.execute(reserveScript, keys, args);
        long status = result.get(0);
        if (status == 1L) {
            log.info("Redis reserve idempotent hit for order {} — skipping decrement", orderId);
            return new ReservationOutcome.Success();
        }
        if (status == 2L) {
            return new ReservationOutcome.Success();
        }
        // status == 0 → insufficient at item index 1-based
        int failedIdx = result.get(1).intValue() - 1;
        long available = result.get(2);
        OrderItemDto failed = items.get(failedIdx);
        return new ReservationOutcome.InsufficientStock(
                failed.productId(), available, failed.quantity());
    }

    @Override
    public void release(UUID orderId, List<ReservationLine> items) {
        List<String> keys = new ArrayList<>(items.size() + 1);
        keys.add(RESERVED_ORDERS_KEY);
        items.forEach(i -> keys.add(stockKey(i.productId())));

        Object[] args = new Object[items.size() + 1];
        args[0] = orderId.toString();
        for (int i = 0; i < items.size(); i++) {
            args[i + 1] = String.valueOf(items.get(i).quantity());
        }

        Long status = redis.execute(releaseScript, keys, args);
        if (status != null && status == 0L) {
            log.info("Redis release idempotent — order {} was not in reserved set", orderId);
        }
    }

    @Override
    public void warm(UUID productId, int stockAvailable) {
        redis.opsForValue().set(stockKey(productId), String.valueOf(stockAvailable));
    }

    @Override
    public long currentStock(UUID productId) {
        String value = redis.opsForValue().get(stockKey(productId));
        return value == null ? 0L : Long.parseLong(value);
    }

    private static String stockKey(UUID productId) {
        return "stock:" + productId;
    }
}
