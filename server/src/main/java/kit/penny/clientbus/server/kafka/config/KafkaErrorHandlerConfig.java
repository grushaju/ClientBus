package kit.penny.clientbus.server.kafka.config;

import jakarta.persistence.EntityNotFoundException;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;
import kit.penny.clientbus.common.kafka.SyncRecentChatsKafkaCommand;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import kit.penny.clientbus.server.service.MessageService;
import kit.penny.clientbus.server.service.RecentChatsSyncCoordinator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

    private final RecentChatsSyncCoordinator
            recentChatsSyncCoordinator;

    public KafkaErrorHandlerConfig(
            RecentChatsSyncCoordinator recentChatsSyncCoordinator
    ) {
        this.recentChatsSyncCoordinator =
                recentChatsSyncCoordinator;
    }

    @Bean
    public DeadLetterPublishingRecoverer
    kafkaDeadLetterPublishingRecoverer(
            KafkaTemplate<String, Object> kafkaTemplate
    ) {
        return new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) ->
                        new TopicPartition(
                                KafkaTopicNames.dlq(
                                        record.topic()
                                ),
                                record.partition()
                        )
        );
    }

    @Bean
    public CommonErrorHandler kafkaCommonErrorHandler(
            DeadLetterPublishingRecoverer
                    deadLetterPublishingRecoverer,
            MessageService messageService
    ) {

        FixedBackOff backOff =
                new FixedBackOff(
                        1000L,
                        3L
                );

        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(
                        (record, exception) -> {

                            releaseRecentChatsSyncRun(
                                    record
                            );

                            markOutboundMessageFailed(
                                    record,
                                    messageService
                            );

                            deadLetterPublishingRecoverer.accept(
                                    record,
                                    exception
                            );
                        },
                        backOff
                );

        errorHandler.addNotRetryableExceptions(
                EntityNotFoundException.class
        );

        return errorHandler;
    }

    private void releaseRecentChatsSyncRun(
            ConsumerRecord<?, ?> record
    ) {

        if (record == null || record.value() == null) {
            return;
        }

        if (!(record.value() instanceof KafkaEvent<?> event)) {
            return;
        }

        if (event.eventType() != KafkaEventType.SYNC_RECENT_CHATS) {
            return;
        }

        if (!(event.payload()
                instanceof SyncRecentChatsKafkaCommand command)) {
            return;
        }

        if (event.eventId() == null) {
            return;
        }

        recentChatsSyncCoordinator.complete(
                command.channelAccountId(),
                event.eventId()
        );
    }

    private void markOutboundMessageFailed(
            ConsumerRecord<?, ?> record,
            MessageService messageService
    ) {
        if (record == null
                || record.value() == null) {
            return;
        }

        if (!(record.value()
                instanceof KafkaEvent<?> event)) {
            return;
        }

        if (!(event.payload()
                instanceof OutboundMessageKafkaCommand command)) {
            return;
        }

        messageService.markDeliveryFailed(
                command.messageId()
        );
    }
}