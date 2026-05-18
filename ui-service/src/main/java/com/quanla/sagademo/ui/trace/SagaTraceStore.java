package com.quanla.sagademo.ui.trace;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory store of saga messages keyed by orderId.
 * <p>
 * CopyOnWriteArrayList per orderId because we read (UI polls) far more often
 * than we write (one append per message), and the entries-per-saga count is
 * small (~6 in the happy path, ~10 with compensation).
 * <p>
 * Bounded: discards trace history older than {@code maxOrders} entries to keep
 * memory predictable under long demo sessions. LRU semantics — when we hit the
 * cap we drop the orderId with the oldest most-recent entry.
 */
@Component
public class SagaTraceStore {

    private static final int MAX_ORDERS = 1000;

    private final Map<UUID, List<SagaTraceEntry>> trace = new ConcurrentHashMap<>();

    public void record(SagaTraceEntry entry) {
        trace.computeIfAbsent(entry.orderId(), k -> new CopyOnWriteArrayList<>()).add(entry);
        if (trace.size() > MAX_ORDERS) evictOldest();
    }

    public List<SagaTraceEntry> getTrace(UUID orderId) {
        List<SagaTraceEntry> list = trace.getOrDefault(orderId, List.of());
        // Defensive sort — Kafka delivery is per-partition-ordered, but events
        // for one orderId can come from different partitions (different topics).
        return list.stream()
                .sorted(Comparator.comparing(SagaTraceEntry::occurredAt))
                .toList();
    }

    private synchronized void evictOldest() {
        if (trace.size() <= MAX_ORDERS) return;
        trace.entrySet().stream()
                .min(Comparator.comparing(e ->
                        e.getValue().get(e.getValue().size() - 1).occurredAt()))
                .ifPresent(e -> trace.remove(e.getKey()));
    }
}
