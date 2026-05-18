package com.quanla.sagademo.common.event.payload;

import java.util.UUID;

public record RefundPaymentCommand(
        UUID orderId,
        UUID paymentId
) {}