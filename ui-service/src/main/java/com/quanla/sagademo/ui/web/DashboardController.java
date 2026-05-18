package com.quanla.sagademo.ui.web;

import com.quanla.sagademo.ui.dto.CreateOrderForm;
import com.quanla.sagademo.ui.dto.OrderView;
import com.quanla.sagademo.ui.service.OrderGateway;
import com.quanla.sagademo.ui.service.ProductGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.view.RedirectView;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final OrderGateway orders;
    private final ProductGateway products;

    @GetMapping("/")
    public String index(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Model model) {
        model.addAttribute("orderPage", orders.listOrders(page, size));
        model.addAttribute("products", products.listProducts());
        model.addAttribute("form", new CreateOrderForm());
        return "index";
    }

    @PostMapping("/orders")
    public RedirectView submitOrder(@ModelAttribute CreateOrderForm form) {
        UUID customerId = form.getCustomerId() != null
                ? form.getCustomerId() : UUID.randomUUID();
        OrderView created = orders.createOrder(
                customerId, form.getProductId(), form.getQuantity(), form.getUnitPrice());
        return new RedirectView("/orders/" + created.orderId());
    }

    @GetMapping("/orders/{id}")
    public String orderDetail(@PathVariable UUID id, Model model) {
        model.addAttribute("order", orders.getOrder(id));
        return "order-detail";
    }

    /**
     * HTMX poll target. Returns just the status fragment so the browser swaps
     * the inner content without reloading the whole page.
     */
    @GetMapping("/orders/{id}/status")
    public String orderStatusFragment(@PathVariable UUID id, Model model) {
        model.addAttribute("order", orders.getOrder(id));
        return "fragments/order-status :: status";
    }
}
