package kit.penny.clientbus.server.connector.host;

import kit.penny.clientbus.connector.api.host.HostInboundPublisher;
import kit.penny.clientbus.connector.api.model.ConnectorInboundMessage;
import kit.penny.clientbus.server.connector.ConnectorPayloadMapper;
import kit.penny.clientbus.server.kafka.producer.IPlatformMessagePublisher;
import org.springframework.stereotype.Component;

/** Transitional 13.2a adapter: connector payloads become the existing platform Kafka payload. */
@Component
public class KafkaHostInboundPublisher
        implements HostInboundPublisher {

    private final IPlatformMessagePublisher platformMessagePublisher;

    public KafkaHostInboundPublisher(
            IPlatformMessagePublisher platformMessagePublisher
    ) {
        this.platformMessagePublisher =
                platformMessagePublisher;
    }

    @Override
    public void publish(
            ConnectorInboundMessage message
    ) {
        platformMessagePublisher.publish(
                ConnectorPayloadMapper.toPlatform(message)
        );
    }
}
