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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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

    /**
     * Paginated list, newest first. Page is 0-based. Default page size 20,
     * capped at 100 to bound the worst-case response size.
     */
    @GetMapping
    public Page<OrderResponse> listOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        PageRequest req = PageRequest.of(Math.max(0, page), safeSize,
                Sort.by("createdAt").descending());
        return orderRepository.findAll(req)
                .map(order -> {
                    SagaInstance saga = sagaRepository.findByOrderId(order.getId()).orElse(null);
                    return OrderResponse.from(order, saga);
                });
    }
}