package com.quanla.sagademo.ui.trace;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the saga timeline view. Captured every time ui-service sees a
 * message land on any of the saga topics.
 */
public record SagaTraceEntry(
        Instant occurredAt,
        String topic,
        String eventType,
        UUID messageId,
        UUID sagaId,
        UUID orderId,
        String payloadPreview
) {
    /**
     * Tag for the UI to colour-code the row:
     *  - SUCCESS — terminal good outcomes (Reserved / Completed / Confirmed)
     *  - FAILURE — bad terminal outcomes (Failed / Cancelled)
     *  - COMPENSATING — release / refund commands
     *  - COMMAND — orchestrator -> participant
     *  - EVENT — participant -> orchestrator
     */
    public String category() {
        if (eventType == null) return "EVENT";
        String t = eventType.toLowerCase();
        if (t.contains("failed") || t.contains("cancelled")) return "FAILURE";
        if (t.startsWith("release") || t.startsWith("refund") || t.contains("released") || t.contains("refunded")) return "COMPENSATING";
        if (t.contains("reserved") || t.contains("completed") || t.contains("confirmed")) return "SUCCESS";
        if (topic != null && topic.endsWith(".commands")) return "COMMAND";
        return "EVENT";
    }
}
