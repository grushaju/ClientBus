
package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.KafkaEventType;
import kit.penny.clientbus.common.kafka.SyncAccountKafkaCommand;
import kit.penny.clientbus.server.connector.ChannelConnectorRegistry;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.command.SyncAccountCommand;
import kit.penny.clientbus.server.kafka.routing.KafkaTopicNames;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class KafkaSyncAccountConsumer {

    private final ChannelConnectorRegistry channelConnectorRegistry;

    public KafkaSyncAccountConsumer(
            ChannelConnectorRegistry channelConnectorRegistry
    ) {
        this.channelConnectorRegistry = channelConnectorRegistry;
    }

    @KafkaListener(
            id = "kafkaSyncAccountConsumer",
            groupId =
                    "${spring.kafka.consumer.connector-command-group-id}",
            topicPattern =
                    "#{T(kit.penny.clientbus.server.kafka.routing.KafkaTopicNames).accountCommandPattern()}"
    )
    public void consume(
            KafkaEvent<SyncAccountKafkaCommand> event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic
    ) {
        if (event == null) {
            throw new IllegalArgumentException(
                    "Kafka sync account event must not be null"
            );
        }

        if (event.eventType() != KafkaEventType.SYNC_ACCOUNT) {
            throw new IllegalArgumentException(
                    "Unsupported Kafka event type: " + event.eventType()
            );
        }

        if (event.payload() == null) {
            throw new IllegalArgumentException(
                    "Kafka sync account payload must not be null"
            );
        }

        UUID channelAccountId = event.payload().channelAccountId();

        if (channelAccountId == null) {
            throw new IllegalArgumentException(
                    "Kafka sync account channelAccountId must not be null"
            );
        }

        ChannelType channelType =
                KafkaTopicNames.accountCommandChannelType(topic);

        IChannelConnector connector =
                channelConnectorRegistry.getConnector(channelType);

        connector.handle(new SyncAccountCommand(channelAccountId));
    }
}
