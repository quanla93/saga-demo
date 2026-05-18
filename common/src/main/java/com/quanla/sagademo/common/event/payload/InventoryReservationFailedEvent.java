package com.quanla.sagademo.common.event.payload;

import java.util.UUID;

public record InventoryReservationFailedEvent(
        UUID orderId,
        String reason
) {}