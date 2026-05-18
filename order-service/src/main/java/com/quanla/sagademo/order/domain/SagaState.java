package com.quanla.sagademo.order.domain;

public enum SagaState {
    STARTED,
    INVENTORY_RESERVED,
    PAYMENT_COMPLETED,
    COMPLETED,
    COMPENSATING_RELEASE_INVENTORY,
    COMPENSATING_REFUND_PAYMENT,
    FAILED
}