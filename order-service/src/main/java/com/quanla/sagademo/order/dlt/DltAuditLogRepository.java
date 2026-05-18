package com.quanla.sagademo.order.dlt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DltAuditLogRepository extends JpaRepository<DltAuditLog, UUID> {
}
