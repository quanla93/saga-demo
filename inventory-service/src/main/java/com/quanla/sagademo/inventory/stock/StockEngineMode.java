package com.quanla.sagademo.inventory.stock;

public enum StockEngineMode {
    /** Pessimistic-lock SELECT FOR UPDATE on Postgres products. */
    DATABASE,
    /** Atomic Redis Lua script with idempotency set. */
    REDIS,
    /**
     * Scheduled scanner ({@link com.quanla.sagademo.inventory.stock.SaleWindowSwitcher})
     * flips the active backend between DATABASE and REDIS based on configured
     * sale windows. While AUTO is set as the *configured* mode, the router's
     * *effective* mode at any moment is always either DATABASE or REDIS.
     */
    AUTO
}
