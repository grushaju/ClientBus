package kit.penny.clientbus.server.kafka.config;

import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import jakarta.persistence.EntityNotFoundException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorHandlerConfig {

    @Bean
    public DeadLetterPublishingRecoverer kafkaDeadLetterPublishingRecoverer(
            KafkaTemplate<String, Object> kafkaTemplate
    ) {

        return new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) ->
                        new TopicPartition(
                                KafkaTopicNames.dlq(record.topic()),
                                record.partition()
                        )
        );
    }

    @Bean
    public CommonErrorHandler kafkaCommonErrorHandler(
            DeadLetterPublishingRecoverer deadLetterPublishingRecoverer
    ) {

        FixedBackOff backOff =
                new FixedBackOff(
                        1000L,
                        3L
                );

        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(
                        deadLetterPublishingRecoverer,
                        backOff
                );

        errorHandler.addNotRetryableExceptions(
                EntityNotFoundException.class
        );

        return errorHandler;
    }
}
