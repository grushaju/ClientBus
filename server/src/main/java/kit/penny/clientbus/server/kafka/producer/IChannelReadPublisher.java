package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.kafka.ChannelReadKafkaCommand;

public interface IChannelReadPublisher {

    void publish(
            ChannelType channelType,
            ChannelReadKafkaCommand command
    );
}