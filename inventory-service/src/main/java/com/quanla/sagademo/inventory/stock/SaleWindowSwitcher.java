package com.quanla.sagademo.inventory.stock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Scheduled switcher for AUTO mode. Runs every 30 seconds; if the current
 * wall-clock time falls inside any configured sale window, the router is
 * flipped to REDIS (with a warm-from-Postgres beforehand if it wasn't
 * already in REDIS). When the window ends, the router is flipped back to
 * DATABASE after a reconcile.
 * <p>
 * Active only when {@code saga.inventory.stock-source=AUTO}. In DATABASE or
 * REDIS mode the operator controls the active engine directly and this
 * scheduler stays quiet.
 * <p>
 * Production-grade alternative: read sale windows from a database table
 * managed by ops + listen to a "sale published" Kafka topic for instant
 * reaction. The 30-second poll here is a deliberate demo simplification.
 */
@Component
@ConditionalOnProperty(name = "saga.inventory.stock-source", havingValue = "AUTO")
@RequiredArgsConstructor
@Slf4j
public class SaleWindowSwitcher {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final StockEngineRouter router;

    @Value("${saga.inventory.sale-windows:}")
    private String saleWindowsRaw;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 5_000L)
    public void evaluate() {
        List<Window> windows = parseWindows(saleWindowsRaw);
        boolean inSale = inAnyWindow(LocalTime.now(), windows);
        StockEngineMode current = router.getEffectiveMode();

        if (inSale && current == StockEngineMode.DATABASE) {
            log.info("AUTO mode: entering sale window — warming Redis and switching to REDIS");
            router.warmFromDatabase();
            router.setEffectiveMode(StockEngineMode.REDIS);
        } else if (!inSale && current == StockEngineMode.REDIS) {
            log.info("AUTO mode: leaving sale window — reconciling Redis → Postgres and switching to DATABASE");
            router.reconcileRedisToDatabase();
            router.setEffectiveMode(StockEngineMode.DATABASE);
        }
    }

    private static List<Window> parseWindows(String raw) {
        List<Window> windows = new ArrayList<>();
        if (raw == null || raw.isBlank()) return windows;
        for (String spec : raw.split(",")) {
            String[] parts = spec.trim().split("-");
            if (parts.length != 2) continue;
            try {
                windows.add(new Window(
                        LocalTime.parse(parts[0].trim(), HHMM),
                        LocalTime.parse(parts[1].trim(), HHMM)));
            } catch (Exception e) {
                log.warn("Ignoring malformed sale window '{}'", spec);
            }
        }
        return windows;
    }

    private static boolean inAnyWindow(LocalTime now, List<Window> windows) {
        for (Window w : windows) {
            if (!now.isBefore(w.start()) && now.isBefore(w.end())) return true;
        }
        return false;
    }

    private record Window(LocalTime start, LocalTime end) {}
}
