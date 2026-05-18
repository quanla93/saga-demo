package com.quanla.sagademo.order.dlt;

import com.quanla.sagademo.common.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DltMessageListener {

    private final DltReplayService dltReplayService;

    @KafkaListener(
            topics = {Topics.INVENTORY_EVENTS + ".DLT", Topics.PAYMENT_EVENTS + ".DLT"},
            groupId = "order-service-dlt-ops"
    )
    public void onDltMessage(ConsumerRecord<String, String> record) {
        DltMessageRecord message = dltReplayService.record(record);
        log.warn("Recorded DLT message id={} topic={} originalTopic={} key={}",
                message.getId(), message.getDltTopic(), message.getOriginalTopic(), message.getMessageKey());
    }
}
