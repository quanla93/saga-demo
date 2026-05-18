package com.quanla.sagademo.order.api;

import com.quanla.sagademo.order.api.dto.CreateOrderRequest;
import com.quanla.sagademo.order.api.dto.OrderResponse;
import com.quanla.sagademo.order.domain.Order;
import com.quanla.sagademo.order.domain.OrderRepository;
import com.quanla.sagademo.order.domain.SagaInstance;
import com.quanla.sagademo.order.domain.SagaInstanceRepository;
import com.quanla.sagademo.order.saga.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaRepository;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Order order = orderService.createOrder(request, idempotencyKey);
        SagaInstance saga = sagaRepository.findByOrderId(order.getId()).orElse(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order, saga));
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        SagaInstance saga = sagaRepository.findByOrderId(order.getId()).orElse(null);
        return OrderResponse.from(order, saga);
    }

    @GetMapping
    public List<OrderResponse> listOrders() {
        return orderRepository.findAll().stream()
                .map(order -> {
                    SagaInstance saga = sagaRepository.findByOrderId(order.getId()).orElse(null);
                    return OrderResponse.from(order, saga);
                })
                .toList();
    }
}