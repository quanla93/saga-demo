package com.quanla.sagademo.inventory.stock;

import com.quanla.sagademo.common.event.payload.OrderItemDto;
import com.quanla.sagademo.inventory.domain.Product;
import com.quanla.sagademo.inventory.domain.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pessimistic-lock implementation. Always wired; the {@link StockEngineRouter}
 * decides at runtime whether to delegate to this engine or the Redis one based
 * on its current mode. Idempotency is handled by the outer
 * {@code InventoryService}, which checks for an existing Reservation row by
 * orderId before calling tryReserve — so this engine needs no extra dedup set.
 */
@Component("databaseStockEngine")
@RequiredArgsConstructor
@Slf4j
public class DatabaseStockEngine implements StockReservationEngine {

    private final ProductRepository productRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationOutcome tryReserve(UUID orderId, List<OrderItemDto> items) {
        List<UUID> productIds = items.stream()
                .map(OrderItemDto::productId)
                .distinct()
                .sorted()
                .toList();
        Map<UUID, Product> productsById = productRepository
                .lockAllByIdOrderedById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        for (OrderItemDto item : items) {
            Product product = productsById.get(item.productId());
            if (product == null) return new ReservationOutcome.UnknownProduct(item.productId());
            if (product.getStockAvailable() < item.quantity()) {
                return new ReservationOutcome.InsufficientStock(
                        item.productId(), product.getStockAvailable(), item.quantity());
            }
        }

        for (OrderItemDto item : items) {
            Product product = productsById.get(item.productId());
            product.setStockAvailable(product.getStockAvailable() - item.quantity());
            product.setStockReserved(product.getStockReserved() + item.quantity());
        }
        return new ReservationOutcome.Success();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID orderId, List<ReservationLine> items) {
        List<UUID> productIds = items.stream()
                .map(ReservationLine::productId)
                .distinct()
                .sorted()
                .toList();
        Map<UUID, Product> productsById = productRepository
                .lockAllByIdOrderedById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        for (ReservationLine item : items) {
            Product product = productsById.get(item.productId());
            if (product == null) continue;
            product.setStockReserved(product.getStockReserved() - item.quantity());
            product.setStockAvailable(product.getStockAvailable() + item.quantity());
        }
    }

    @Override
    public void warm(UUID productId, int stockAvailable) {
        // No-op: database is already the source of truth.
    }

    @Override
    public long currentStock(UUID productId) {
        return productRepository.findById(productId)
                .map(Product::getStockAvailable)
                .orElse(0);
    }
}
