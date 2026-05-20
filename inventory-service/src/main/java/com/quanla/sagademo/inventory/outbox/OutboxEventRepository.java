package com.quanla.sagademo.inventory.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("SELECT o FROM OutboxEvent o WHERE o.publishedAt IS NULL AND o.parkedAt IS NULL ORDER BY o.occurredAt ASC")
    List<OutboxEvent> findUnpublished(Pageable pageable);
}
