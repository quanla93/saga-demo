package com.quanla.sagademo.ui.trace;

import com.quanla.sagademo.ui.service.OrderGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.UUID;

/**
 * Renders a per-order saga timeline (logs the orchestrator's view of every
 * message that touched the order). Backed by {@link SagaTraceStore} which is
 * populated by {@link SagaTraceCollector} reading the saga topics.
 */
@Controller
@RequiredArgsConstructor
public class SagaTraceController {

    private final SagaTraceStore store;
    private final OrderGateway orders;

    @GetMapping("/saga/{orderId}")
    public String tracePage(@PathVariable UUID orderId, Model model) {
        model.addAttribute("order", orders.getOrder(orderId));
        model.addAttribute("entries", store.getTrace(orderId));
        return "saga-trace";
    }

    /**
     * HTMX poll target. Returns just the timeline fragment so the page can
     * refresh without re-rendering the header.
     */
    @GetMapping("/saga/{orderId}/timeline")
    public String timelineFragment(@PathVariable UUID orderId, Model model) {
        List<SagaTraceEntry> entries = store.getTrace(orderId);
        model.addAttribute("entries", entries);
        model.addAttribute("order", orders.getOrder(orderId));
        return "fragments/saga-trace :: timeline";
    }
}
