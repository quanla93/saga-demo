package com.quanla.sagademo.common.event.payload;

import java.util.UUID;

public record ReleaseInventoryCommand(
        UUID orderId,
        UUID reservationId
) {}