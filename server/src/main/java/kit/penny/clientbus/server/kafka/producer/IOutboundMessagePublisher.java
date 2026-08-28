package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;

public interface IOutboundMessagePublisher {

    void publish(
            ChannelType channelType,
            OutboundMessageKafkaCommand command
    );
}