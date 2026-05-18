package com.quanla.sagademo.common.event.payload;

import java.util.UUID;

public record InventoryReservedEvent(
        UUID orderId,
        UUID reservationId
) {}