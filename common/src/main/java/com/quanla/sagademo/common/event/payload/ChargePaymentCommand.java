package com.quanla.sagademo.common.event.payload;

import java.math.BigDecimal;
import java.util.UUID;

public record ChargePaymentCommand(
        UUID orderId,
        UUID customerId,
        BigDecimal amount
) {}