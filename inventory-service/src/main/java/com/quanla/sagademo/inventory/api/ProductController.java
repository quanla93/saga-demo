package com.quanla.sagademo.inventory.api;

import com.quanla.sagademo.inventory.domain.Product;
import com.quanla.sagademo.inventory.domain.ProductRepository;
import com.quanla.sagademo.inventory.stock.StockReservationEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;
    private final StockReservationEngine engine;

    /**
     * Returns the live view of stock as the active engine sees it. In REDIS
     * mode {@code stock_available} comes from the Redis key (the real-time
     * truth), while in DATABASE mode it comes from Postgres directly.
     * {@code stock_reserved} is computed as the difference between the
     * Postgres-recorded total (available + reserved) and the live available
     * count — i.e. "how many units are currently locked behind in-flight
     * reservations relative to the post-seed snapshot".
     */
    @GetMapping
    public List<ProductView> listProducts() {
        return productRepository.findAll().stream()
                .map(this::toView)
                .toList();
    }

    private ProductView toView(Product p) {
        long liveAvailable = engine.currentStock(p.getId());
        int total = p.getStockAvailable() + p.getStockReserved();
        int liveReserved = (int) Math.max(0, total - liveAvailable);
        return new ProductView(p.getId(), p.getSku(), p.getName(),
                (int) liveAvailable, liveReserved);
    }

    public record ProductView(
            UUID id,
            String sku,
            String name,
            int stockAvailable,
            int stockReserved
    ) {}
}
