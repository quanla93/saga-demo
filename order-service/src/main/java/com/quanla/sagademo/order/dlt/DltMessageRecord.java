package com.quanla.sagademo.order.dlt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dlt_messages")
@Getter
@NoArgsConstructor
public class DltMessageRecord {

    @Id
    private UUID id;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "original_topic")
    private String originalTopic;

    @Column(name = "dlt_topic")
    private String dltTopic;

    @Column(name = "message_key")
    private String messageKey;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(columnDefinition = "TEXT")
    @Column(name = "headers_json", columnDefinition = "TEXT")
    private String headersJson;

    @Column(name = "exception_class", columnDefinition = "TEXT")
    private String exceptionClass;

    @Column(name = "exception_message", columnDefinition = "TEXT")
    private String exceptionMessage;

    @Enumerated(EnumType.STRING)
    private DltMessageStatus status;

    @Column(name = "replay_attempts")
    private int replayAttempts;

    @Column(name = "first_seen_at")
    private Instant firstSeenAt;

    @Column(name = "last_action_at")
    private Instant lastActionAt;

    public DltMessageRecord(UUID messageId, String originalTopic, String dltTopic, String messageKey,
                            String payload, String headersJson, String exceptionClass, String exceptionMessage) {
        this.id = UUID.randomUUID();
        this.messageId = messageId;
        this.originalTopic = originalTopic;
        this.dltTopic = dltTopic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.headersJson = headersJson;
        this.exceptionClass = exceptionClass;
        this.exceptionMessage = exceptionMessage;
        this.status = DltMessageStatus.NEW;
        this.firstSeenAt = Instant.now();
    }

    public void markReplayed() {
        this.status = DltMessageStatus.REPLAYED;
        this.replayAttempts++;
        this.lastActionAt = Instant.now();
    }

    public void markReplayFailed(String error) {
        this.status = DltMessageStatus.REPLAY_FAILED;
        this.replayAttempts++;
        this.exceptionMessage = error;
        this.lastActionAt = Instant.now();
    }

    public void quarantine() {
        this.status = DltMessageStatus.QUARANTINED;
        this.lastActionAt = Instant.now();
    }
}
