package com.quanla.sagademo.ui.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemView(
        UUID productId,
        int quantity,
        BigDecimal unitPrice
) {}
