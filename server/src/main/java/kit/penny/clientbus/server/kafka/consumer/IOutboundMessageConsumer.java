package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.OutboundMessageKafkaCommand;

public interface IOutboundMessageConsumer {

    void consume(
            KafkaEvent<OutboundMessageKafkaCommand> event,
            String topic
    );
}