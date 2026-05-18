package com.quanla.sagademo.inventory.stock;

import com.quanla.sagademo.common.event.payload.OrderItemDto;
import com.quanla.sagademo.inventory.domain.Product;
import com.quanla.sagademo.inventory.domain.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime-switchable façade over {@link DatabaseStockEngine} and
 * {@link RedisStockEngine}.
 * <p>
 * Mode can flip without a restart through three paths:
 * <ol>
 *   <li>Admin REST: {@code POST /admin/stock-engine/{DATABASE|REDIS}} (operator
 *       override, e.g. emergency rollback if Redis misbehaves)
 *   <li>Configured mode = {@code AUTO}: {@link SaleWindowSwitcher} sets the
 *       effective mode based on the current time against configured sale
 *       windows
 *   <li>Boot: initial effective mode comes from
 *       {@code saga.inventory.stock-source} — {@code DATABASE} / {@code REDIS}
 *       / {@code AUTO}
 * </ol>
 * When flipping from REDIS → DATABASE, callers should first invoke
 * {@link #reconcileRedisToDatabase()} to write Redis stock values back into
 * Postgres so the pessimistic-lock engine doesn't see stale counts. When
 * flipping DATABASE → REDIS, {@link #warmFromDatabase()} reseeds Redis.
 */
@Component
@Primary
@Slf4j
public class StockEngineRouter implements StockReservationEngine {

    private final DatabaseStockEngine databaseEngine;
    private final RedisStockEngine redisEngine;
    private final ProductRepository productRepository;
    private final AtomicReference<StockEngineMode> effectiveMode;

    public StockEngineRouter(
            @Qualifier("databaseStockEngine") DatabaseStockEngine databaseEngine,
            @Qualifier("redisStockEngine") RedisStockEngine redisEngine,
            ProductRepository productRepository,
            @Value("${saga.inventory.stock-source:REDIS}") StockEngineMode configuredMode) {
        this.databaseEngine = databaseEngine;
        this.redisEngine = redisEngine;
        this.productRepository = productRepository;
        // AUTO is resolved by the scheduled switcher; initial effective mode
        // is DATABASE (safe default) until the first scheduler tick fires.
        StockEngineMode initial = configuredMode == StockEngineMode.AUTO
                ? StockEngineMode.DATABASE
                : configuredMode;
        this.effectiveMode = new AtomicReference<>(initial);
        log.info("StockEngineRouter starting — configured={}, initial effective={}",
                configuredMode, initial);
    }

    public StockEngineMode getEffectiveMode() {
        return effectiveMode.get();
    }

    /**
     * Atomically swap the active engine. Returns the previous mode so the
     * caller can decide whether warm/reconcile is needed.
     */
    public StockEngineMode setEffectiveMode(StockEngineMode newMode) {
        if (newMode == StockEngineMode.AUTO) {
            throw new IllegalArgumentException(
                    "AUTO is a configured mode, not an effective mode — switch to DATABASE or REDIS");
        }
        StockEngineMode old = effectiveMode.getAndSet(newMode);
        if (old != newMode) {
            log.warn("Stock engine mode changed: {} → {}", old, newMode);
        }
        return old;
    }

    /**
     * Push Postgres {@code products.stock_available} into the Redis engine.
     * Call before flipping DATABASE → REDIS so the Redis keys reflect current
     * stock, not stale or zero values.
     */
    public void warmFromDatabase() {
        int n = 0;
        for (Product p : productRepository.findAll()) {
            redisEngine.warm(p.getId(), p.getStockAvailable());
            n++;
        }
        log.info("Warmed Redis from Postgres: {} products", n);
    }

    /**
     * Write Redis stock values back into Postgres {@code products.stock_available}.
     * Call before flipping REDIS → DATABASE so the pessimistic-lock engine sees
     * the true post-sale inventory.
     */
    public void reconcileRedisToDatabase() {
        int n = 0;
        for (Product p : productRepository.findAll()) {
            long redisValue = redisEngine.currentStock(p.getId());
            if (redisValue != p.getStockAvailable()) {
                log.info("Reconciling product {} stock {} → {}",
                        p.getId(), p.getStockAvailable(), redisValue);
                p.setStockAvailable((int) redisValue);
            }
            n++;
        }
        productRepository.saveAll(productRepository.findAll());
        log.info("Reconciled {} products from Redis back to Postgres", n);
    }

    private StockReservationEngine active() {
        return effectiveMode.get() == StockEngineMode.REDIS ? redisEngine : databaseEngine;
    }

    @Override
    public ReservationOutcome tryReserve(UUID orderId, List<OrderItemDto> items) {
        return active().tryReserve(orderId, items);
    }

    @Override
    public void release(UUID orderId, List<ReservationLine> items) {
        active().release(orderId, items);
    }

    @Override
    public void warm(UUID productId, int stockAvailable) {
        // Always seed Redis when warming. The DB engine treats this as a no-op.
        redisEngine.warm(productId, stockAvailable);
    }

    @Override
    public long currentStock(UUID productId) {
        return active().currentStock(productId);
    }
}
