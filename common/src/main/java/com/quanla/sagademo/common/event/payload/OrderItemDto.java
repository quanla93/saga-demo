package com.quanla.sagademo.common.event.payload;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemDto(
        UUID productId,
        int quantity,
        BigDecimal unitPrice
) {}