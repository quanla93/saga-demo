package com.quanla.sagademo.common.event.payload;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentCompletedEvent(
        UUID orderId,
        UUID paymentId,
        BigDecimal amount
) {}