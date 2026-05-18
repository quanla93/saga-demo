package com.quanla.sagademo.ui.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class CreateOrderForm {
    private UUID customerId;
    private UUID productId;
    private int quantity;
    private BigDecimal unitPrice;
}
