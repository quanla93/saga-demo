package com.quanla.sagademo.inventory.inbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InboxMessageRepository extends JpaRepository<InboxMessage, UUID> {
}
