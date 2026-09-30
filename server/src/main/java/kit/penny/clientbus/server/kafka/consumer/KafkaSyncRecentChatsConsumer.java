package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.SyncRecentChatsKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.command.SyncRecentChatsCommand;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class KafkaSyncRecentChatsConsumer {

    private final ChannelConnectorRegistry
            channelConnectorRegistry;

    public KafkaSyncRecentChatsConsumer(
            ChannelConnectorRegistry channelConnectorRegistry
    ) {
        this.channelConnectorRegistry =
                channelConnectorRegistry;
    }

    @KafkaListener(
            id = "kafkaSyncRecentChatsConsumer",
            groupId =
                    "${spring.kafka.consumer.connector-command-group-id}",
            topicPattern =
                    "#{T(kit.penny.clientbus.server.kafka.routing.KafkaTopicNames).channelCommandPattern()}"
    )
    public void consume(
            KafkaEvent<SyncRecentChatsKafkaCommand> event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic
    ) {

        if (event == null) {
            throw new IllegalArgumentException(
                    "Kafka sync recent chats event must not be null"
            );
        }

        if (event.eventType()
                != KafkaEventType.SYNC_RECENT_CHATS) {

            throw new IllegalArgumentException(
                    "Unsupported Kafka event type: "
                            + event.eventType()
            );
        }

        if (event.payload() == null) {
            throw new IllegalArgumentException(
                    "Kafka sync recent chats payload must not be null"
            );
        }

        ChannelType channelType =
                KafkaTopicNames.channelCommandChannelType(
                        topic
                );

        SyncRecentChatsKafkaCommand payload =
                event.payload();

        SyncRecentChatsCommand command =
                new SyncRecentChatsCommand(
                        payload.channelAccountId()
                );

        IChannelConnector connector =
                channelConnectorRegistry.getConnector(
                        channelType
                );

        connector.handle(command);
    }
}