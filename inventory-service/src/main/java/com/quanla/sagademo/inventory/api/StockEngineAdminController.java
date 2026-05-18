package com.quanla.sagademo.inventory.api;

import com.quanla.sagademo.inventory.stock.StockEngineMode;
import com.quanla.sagademo.inventory.stock.StockEngineRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Operator-facing controls for the stock engine. In production these would sit
 * behind an admin auth + audit log; here they're open for the demo.
 */
@RestController
@RequestMapping("/admin/stock-engine")
@RequiredArgsConstructor
public class StockEngineAdminController {

    private final StockEngineRouter router;

    @GetMapping
    public Map<String, Object> status() {
        return Map.of("effectiveMode", router.getEffectiveMode());
    }

    /**
     * Manually flip the active engine. Performs the appropriate warm /
     * reconcile so the new engine starts from a consistent view of stock.
     */
    @PostMapping("/{mode}")
    public Map<String, Object> setMode(@PathVariable StockEngineMode mode) {
        StockEngineMode previous = router.getEffectiveMode();
        if (mode == StockEngineMode.AUTO) {
            throw new IllegalArgumentException(
                    "Admin endpoint cannot select AUTO — restart with saga.inventory.stock-source=AUTO");
        }
        if (previous != mode) {
            if (previous == StockEngineMode.REDIS && mode == StockEngineMode.DATABASE) {
                router.reconcileRedisToDatabase();
            } else if (previous == StockEngineMode.DATABASE && mode == StockEngineMode.REDIS) {
                router.warmFromDatabase();
            }
            router.setEffectiveMode(mode);
        }
        return Map.of("previousMode", previous, "effectiveMode", router.getEffectiveMode());
    }
}
