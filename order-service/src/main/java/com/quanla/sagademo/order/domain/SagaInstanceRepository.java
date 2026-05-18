package com.quanla.sagademo.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SagaInstanceRepository extends JpaRepository<SagaInstance, UUID> {

    Optional<SagaInstance> findByOrderId(UUID orderId);

    /**
     * Returns sagas that are still in a non-terminal state and have not been
     * updated since {@code threshold}. Used by the timeout scanner to detect
     * stuck sagas — e.g. a participant crashed mid-flow or a Kafka message
     * was lost before retry exhausted itself into the DLT.
     */
    @Query("""
            SELECT s FROM SagaInstance s
             WHERE s.state IN (
                 com.quanla.sagademo.order.domain.SagaState.STARTED,
                 com.quanla.sagademo.order.domain.SagaState.INVENTORY_RESERVED,
                 com.quanla.sagademo.order.domain.SagaState.COMPENSATING_RELEASE_INVENTORY,
                 com.quanla.sagademo.order.domain.SagaState.COMPENSATING_REFUND_PAYMENT)
               AND s.updatedAt < :threshold
            """)
    List<SagaInstance> findStuckSince(@Param("threshold") Instant threshold);
}
