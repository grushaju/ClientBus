package kit.penny.clientbus.server.kafka.consumer;

import kit.penny.clientbus.common.kafka.KafkaEvent;
import kit.penny.clientbus.common.kafka.PlatformMessageKafkaEvent;

public interface IPlatformEventConsumer {

    void consume(
            KafkaEvent<PlatformMessageKafkaEvent> event
    );
}
