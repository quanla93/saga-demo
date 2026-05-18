package com.quanla.sagademo.inventory.stock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic sync of Redis stock counts back into Postgres.
 * <p>
 * When the engine is in REDIS mode, every reservation decrements Redis but
 * leaves the Postgres {@code products.stock_available} column untouched. If
 * we never reconciled:
 * <ul>
 *   <li>On restart, {@link StockCacheWarmer} would re-seed Redis from the
 *       STALE Postgres value -- effectively wiping in-window sales.
 *   <li>Reports / analytics reading from Postgres would lie about stock.
 *   <li>The UI product listing would show pre-sale counts instead of live ones.
 * </ul>
 * Runs every 30 s (configurable). Idle when the active engine is DATABASE.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockReconciler {

    private final StockEngineRouter router;

    @Scheduled(
            fixedDelayString = "${saga.inventory.reconcile-interval-ms:30000}",
            initialDelayString = "${saga.inventory.reconcile-interval-ms:30000}")
    public void reconcile() {
        if (router.getEffectiveMode() != StockEngineMode.REDIS) return;
        try {
            router.reconcileRedisToDatabase();
        } catch (Exception e) {
            log.warn("Periodic Redis -> Postgres reconcile failed (will retry next cycle): {}",
                    e.getMessage());
        }
    }
}
