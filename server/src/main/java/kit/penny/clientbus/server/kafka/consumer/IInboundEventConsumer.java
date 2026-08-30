package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.dto.message.PlatformInboundMessageEvent;
import kit.penny.clientbus.common.kafka.KafkaEvent;

public interface IInboundEventConsumer {

    void consume(
            KafkaEvent<PlatformInboundMessageEvent> event
    );
}