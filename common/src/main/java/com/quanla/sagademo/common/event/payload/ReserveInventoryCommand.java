package com.quanla.sagademo.common.event.payload;

import java.util.List;
import java.util.UUID;

public record ReserveInventoryCommand(
        UUID orderId,
        List<OrderItemDto> items
) {}