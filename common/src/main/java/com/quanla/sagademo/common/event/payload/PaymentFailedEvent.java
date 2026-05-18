package com.quanla.sagademo.common.event.payload;

import java.util.UUID;

public record PaymentFailedEvent(
        UUID orderId,
        String reason
) {}