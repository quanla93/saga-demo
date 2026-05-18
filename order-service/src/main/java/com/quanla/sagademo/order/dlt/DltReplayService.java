package com.quanla.sagademo.order.dlt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanla.sagademo.common.event.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DltReplayService {

    private final DltMessageRepository dltMessageRepository;
    private final DltAuditLogRepository auditLogRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public DltMessageRecord record(ConsumerRecord<String, String> record) {
        return dltMessageRepository.findByDltTopicAndMessageKeyAndPayload(record.topic(), record.key(), record.value())
                .orElseGet(() -> dltMessageRepository.save(toDltMessage(record)));
    }

    @Transactional
    public DltMessageRecord replay(UUID id, String operator, String reason) {
        DltMessageRecord message = dltMessageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("DLT message not found: " + id));
        if (message.getStatus() == DltMessageStatus.QUARANTINED) {
            throw new IllegalStateException("Quarantined DLT message cannot be replayed");
        }

        try {
            kafkaTemplate.send(message.getOriginalTopic(), message.getMessageKey(), message.getPayload()).get();
            message.markReplayed();
            auditLogRepository.save(new DltAuditLog(message, "REPLAY", operator, reason));
            return message;
        } catch (Exception ex) {
            message.markReplayFailed(ex.getMessage());
            auditLogRepository.save(new DltAuditLog(message, "REPLAY_FAILED", operator, ex.getMessage()));
            return message;
        }
    }

    @Transactional
    public DltMessageRecord quarantine(UUID id, String operator, String reason) {
        DltMessageRecord message = dltMessageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("DLT message not found: " + id));
        message.quarantine();
        auditLogRepository.save(new DltAuditLog(message, "QUARANTINE", operator, reason));
        return message;
    }

    private DltMessageRecord toDltMessage(ConsumerRecord<String, String> record) {
        return new DltMessageRecord(
                extractMessageId(record.value()),
                originalTopic(record.topic()),
                record.topic(),
                record.key(),
                record.value(),
                headersJson(record),
                header(record, "kafka_dlt-exception-fqcn"),
                header(record, "kafka_dlt-exception-message")
        );
    }

    private UUID extractMessageId(String payload) {
        try {
            return objectMapper.readValue(payload, EventEnvelope.class).messageId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String originalTopic(String dltTopic) {
        return dltTopic.endsWith(".DLT") ? dltTopic.substring(0, dltTopic.length() - 4) : dltTopic;
    }

    private String headersJson(ConsumerRecord<String, String> record) {
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            for (Header header : record.headers()) {
                headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
            }
            return objectMapper.writeValueAsString(headers);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
