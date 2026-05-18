package com.quanla.sagademo.inventory.stock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * On startup, seed Redis stock keys from Postgres so the Redis engine is
 * usable the instant the router flips to REDIS mode. Even when the initial
 * effective mode is DATABASE we pre-warm: an operator may flip to REDIS via
 * the admin endpoint and we don't want the first request after that flip to
 * fail with "insufficient stock" against empty keys.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class StockCacheWarmer {

    private final StockEngineRouter router;

    @Bean
    public ApplicationRunner warmStockCache() {
        return args -> {
            router.warmFromDatabase();
            log.info("Initial Redis warm complete; current effective mode={}",
                    router.getEffectiveMode());
        };
    }
}
