package com.quanla.sagademo.ui.service;

import com.quanla.sagademo.ui.dto.OrderPage;
import com.quanla.sagademo.ui.dto.OrderView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thin gateway around order-service REST. Lives in ui-service so the templates
 * don't talk HTTP directly.
 */
@Service
@RequiredArgsConstructor
public class OrderGateway {

    private final RestClient orderRestClient;

    public OrderPage listOrders(int page, int size) {
        return orderRestClient.get()
                .uri(uri -> uri.path("/api/orders")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build())
                .retrieve()
                .body(OrderPage.class);
    }

    public OrderView getOrder(UUID id) {
        return orderRestClient.get()
                .uri("/api/orders/{id}", id)
                .retrieve()
                .body(OrderView.class);
    }

    public OrderView createOrder(UUID customerId, UUID productId, int quantity, java.math.BigDecimal unitPrice) {
        Map<String, Object> body = Map.of(
                "customerId", customerId,
                "items", List.of(Map.of(
                        "productId", productId,
                        "quantity", quantity,
                        "unitPrice", unitPrice
                ))
        );
        return orderRestClient.post()
                .uri("/api/orders")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(OrderView.class);
    }
}
