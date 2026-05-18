package com.quanla.sagademo.common.event.payload;

import java.util.UUID;

public record PaymentRefundedEvent(
        UUID orderId,
        UUID paymentId
) {}