package com.quanla.sagademo.payment.api;

import com.quanla.sagademo.payment.api.dto.PaymentResponse;
import com.quanla.sagademo.payment.domain.Payment;
import com.quanla.sagademo.payment.domain.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Read-only view of payments. Charges happen via the Kafka command listener,
 * not over HTTP — this controller exists so external observers (Swagger UI,
 * the test scripts, the dashboard) can see what payment-service did in
 * response to a saga.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentRepository repository;

    @GetMapping
    public List<PaymentResponse> list() {
        return repository.findAll().stream().map(PaymentResponse::from).toList();
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        Payment p = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        return PaymentResponse.from(p);
    }

    @GetMapping("/order/{orderId}")
    public PaymentResponse getByOrder(@PathVariable UUID orderId) {
        Payment p = repository.findByOrderId(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No payment for order " + orderId));
        return PaymentResponse.from(p);
    }
}
