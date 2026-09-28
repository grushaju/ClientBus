package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;

public interface IPlatformMessagePublisher {

    void publish(
            PlatformMessageRequest message
    );
}