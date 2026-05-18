package com.quanla.sagademo.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Lua scripts for atomic stock check + decrement. Always registered — the
 * {@link com.quanla.sagademo.inventory.stock.StockEngineRouter} decides at
 * runtime whether the Redis engine is currently the active backend.
 */
@Configuration
public class RedisConfig {

    /**
     * KEYS[1]            = reserved-orders set
     * KEYS[2..N+1]       = stock:{productId} keys, in input order
     * ARGV[1]            = orderId (string)
     * ARGV[2..N+1]       = quantities, aligned with KEYS[2..N+1]
     *
     * Returns:
     *   {1}                          → orderId already reserved → no-op success
     *   {2}                          → fresh success (all stock decremented)
     *   {0, failedIndex, available}  → first SKU that didn't have enough stock
     *                                  (failedIndex is 1-based into items)
     */
    @Bean
    public RedisScript<List> reserveScript() {
        String lua =
                "if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then\n" +
                "  return {1}\n" +
                "end\n" +
                "for i = 2, #KEYS do\n" +
                "  local cur = tonumber(redis.call('GET', KEYS[i]) or '0')\n" +
                "  local need = tonumber(ARGV[i])\n" +
                "  if cur < need then\n" +
                "    return {0, i - 1, cur}\n" +
                "  end\n" +
                "end\n" +
                "for i = 2, #KEYS do\n" +
                "  redis.call('DECRBY', KEYS[i], tonumber(ARGV[i]))\n" +
                "end\n" +
                "redis.call('SADD', KEYS[1], ARGV[1])\n" +
                "return {2}\n";
        return new DefaultRedisScript<>(lua, List.class);
    }

    /**
     * Same key layout as reserveScript. If the orderId is not in the reserved
     * set, the script is a no-op (returns 0) so repeated releases never
     * over-restore stock.
     */
    @Bean
    public RedisScript<Long> releaseScript() {
        String lua =
                "if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 0 then\n" +
                "  return 0\n" +
                "end\n" +
                "for i = 2, #KEYS do\n" +
                "  redis.call('INCRBY', KEYS[i], tonumber(ARGV[i]))\n" +
                "end\n" +
                "redis.call('SREM', KEYS[1], ARGV[1])\n" +
                "return 1\n";
        return new DefaultRedisScript<>(lua, Long.class);
    }
}
