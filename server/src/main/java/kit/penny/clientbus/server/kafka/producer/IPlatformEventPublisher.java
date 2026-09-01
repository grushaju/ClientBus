package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;

import java.util.UUID;

public interface IPlatformEventPublisher {

    void publish(
            PlatformMessageEvent event,
            UUID correlationId
    );
}