package com.quanla.sagademo.order.dlt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DltMessageRepository extends JpaRepository<DltMessageRecord, UUID> {

    List<DltMessageRecord> findTop100ByStatusOrderByFirstSeenAtDesc(DltMessageStatus status);

    Optional<DltMessageRecord> findByDltTopicAndMessageKeyAndPayload(String dltTopic, String messageKey, String payload);
}
