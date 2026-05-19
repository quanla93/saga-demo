package com.quanla.sagademo.inventory.stock;

import com.quanla.sagademo.common.event.payload.OrderItemDto;
import com.quanla.sagademo.inventory.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = RedisStockEngineIntegrationTest.TestConfig.class)
class RedisStockEngineIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private RedisStockEngine stockEngine;

    @Autowired
    private StringRedisTemplate redis;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void clearRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void freshReservationDecrementsStockOnce() {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        stockEngine.warm(productId, 10);

        ReservationOutcome outcome = stockEngine.tryReserve(orderId, items(productId, 3));

        assertThat(outcome).isInstanceOf(ReservationOutcome.Success.class);
        assertThat(stockEngine.currentStock(productId)).isEqualTo(7);
    }

    @Test
    void duplicateReservationForSameOrderIsIdempotent() {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        stockEngine.warm(productId, 10);

        stockEngine.tryReserve(orderId, items(productId, 3));
        ReservationOutcome duplicateOutcome = stockEngine.tryReserve(orderId, items(productId, 3));

        assertThat(duplicateOutcome).isInstanceOf(ReservationOutcome.Success.class);
        assertThat(stockEngine.currentStock(productId)).isEqualTo(7);
    }

    @Test
    void concurrentHotSkuReservationsNeverOversell() throws Exception {
        UUID productId = UUID.randomUUID();
        int initialStock = 20;
        stockEngine.warm(productId, initialStock);

        List<Callable<Boolean>> attempts = IntStream.range(0, 50)
                .mapToObj(i -> (Callable<Boolean>) () -> stockEngine.tryReserve(UUID.randomUUID(), items(productId, 1)).isSuccess())
                .toList();

        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            List<Boolean> results = executor.invokeAll(attempts).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();

            long successes = results.stream().filter(Boolean::booleanValue).count();
            long remaining = stockEngine.currentStock(productId);

            assertThat(successes).isLessThanOrEqualTo(initialStock);
            assertThat(remaining).isNotNegative();
            assertThat(successes + remaining).isEqualTo(initialStock);
        }
    }

    @Test
    void releaseIsIdempotent() {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        stockEngine.warm(productId, 10);
        stockEngine.tryReserve(orderId, items(productId, 3));

        stockEngine.release(orderId, List.of(new ReservationLine(productId, 3)));
        stockEngine.release(orderId, List.of(new ReservationLine(productId, 3)));

        assertThat(stockEngine.currentStock(productId)).isEqualTo(10);
    }

    private static List<OrderItemDto> items(UUID productId, int quantity) {
        return List.of(new OrderItemDto(productId, quantity, BigDecimal.TEN));
    }

    @TestConfiguration
    @Import({RedisStockEngine.class, RedisConfig.class})
    @ImportAutoConfiguration(RedisAutoConfiguration.class)
    static class TestConfig {
    }
}
