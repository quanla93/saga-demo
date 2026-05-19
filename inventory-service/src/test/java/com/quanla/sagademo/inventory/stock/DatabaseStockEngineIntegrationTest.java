package com.quanla.sagademo.inventory.stock;

import com.quanla.sagademo.common.event.payload.OrderItemDto;
import com.quanla.sagademo.inventory.domain.Product;
import com.quanla.sagademo.inventory.domain.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = DatabaseStockEngineIntegrationTest.TestConfig.class)
class DatabaseStockEngineIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DatabaseStockEngine stockEngine;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        productRepository.deleteAll();
    }

    @Test
    void successfulReservationDecrementsAvailableAndIncrementsReserved() {
        UUID productId = seedProduct(10);

        ReservationOutcome outcome = reserve(UUID.randomUUID(), productId, 3);
        Product product = productRepository.findById(productId).orElseThrow();

        assertThat(outcome).isInstanceOf(ReservationOutcome.Success.class);
        assertThat(product.getStockAvailable()).isEqualTo(7);
        assertThat(product.getStockReserved()).isEqualTo(3);
    }

    @Test
    void insufficientStockDoesNotPartiallyUpdateProduct() {
        UUID productId = seedProduct(2);

        ReservationOutcome outcome = reserve(UUID.randomUUID(), productId, 3);
        Product product = productRepository.findById(productId).orElseThrow();

        assertThat(outcome).isInstanceOf(ReservationOutcome.InsufficientStock.class);
        assertThat(product.getStockAvailable()).isEqualTo(2);
        assertThat(product.getStockReserved()).isZero();
    }

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        UUID productId = seedProduct(20);
        List<Callable<Boolean>> attempts = IntStream.range(0, 50)
                .mapToObj(i -> (Callable<Boolean>) () -> reserve(UUID.randomUUID(), productId, 1).isSuccess())
                .toList();

        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            long successes = executor.invokeAll(attempts).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .filter(Boolean::booleanValue)
                    .count();
            Product product = productRepository.findById(productId).orElseThrow();

            assertThat(successes).isLessThanOrEqualTo(20);
            assertThat(product.getStockAvailable()).isNotNegative();
            assertThat(successes + product.getStockAvailable()).isEqualTo(20);
            assertThat(product.getStockReserved()).isEqualTo(successes);
        }
    }

    private UUID seedProduct(int stockAvailable) {
        UUID productId = UUID.randomUUID();
        productRepository.save(Product.builder()
                .id(productId)
                .sku("SKU-" + productId)
                .name("Test product")
                .stockAvailable(stockAvailable)
                .stockReserved(0)
                .build());
        return productId;
    }

    private ReservationOutcome reserve(UUID orderId, UUID productId, int quantity) {
        return tx.execute(status -> stockEngine.tryReserve(orderId, items(productId, quantity)));
    }

    private static List<OrderItemDto> items(UUID productId, int quantity) {
        return List.of(new OrderItemDto(productId, quantity, BigDecimal.TEN));
    }

    @TestConfiguration
    @Import(DatabaseStockEngine.class)
    @EntityScan("com.quanla.sagademo.inventory.domain")
    @EnableJpaRepositories("com.quanla.sagademo.inventory.domain")
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    static class TestConfig {
    }
}
