package com.quanla.sagademo.order.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer retry policy.
 * <p>
 * On any failure not in {@code addNotRetryableExceptions}, the listener container
 * re-delivers the record up to 5 times total (1 original + 4 retries) with a
 * 1-second back-off. After the 5th failure, the record is published to
 * {@code <topic>.DLT} (Spring's default Dead Letter Topic naming) and the
 * consumer commits the offset so the partition can move on.
 * <p>
 * Why this is safe even with the Inbox table: the inbox-guard insert and the
 * business effect commit together inside the {@code @KafkaListener} method.
 * If the method throws, the transaction rolls back → no inbox row → the next
 * retry is genuinely fresh, not a duplicate.
 */
@Configuration
public class KafkaErrorHandlerConfig {

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
        // FixedBackOff(interval, maxAttempts) → maxAttempts is the number of RETRIES
        // after the initial delivery, so 4 retries = 5 total attempts.
        FixedBackOff backOff = new FixedBackOff(1000L, 4);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Programming / data errors won't fix themselves on retry — fail fast to DLT.
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                IllegalStateException.class,
                JsonProcessingException.class);
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
