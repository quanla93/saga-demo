package com.quanla.sagademo.order.dlt;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Column;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dlt_audit_logs")
@Getter
@NoArgsConstructor
public class DltAuditLog {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dlt_message_id", nullable = false)
    private DltMessageRecord dltMessage;

    private String action;

    @Column(name = "operator_name")
    private String operatorName;

    private String reason;
    private Instant createdAt;

    public DltAuditLog(DltMessageRecord dltMessage, String action, String operatorName, String reason) {
        this.id = UUID.randomUUID();
        this.dltMessage = dltMessage;
        this.action = action;
        this.operatorName = operatorName;
        this.reason = reason;
        this.createdAt = Instant.now();
    }
}
