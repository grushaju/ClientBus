package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.dto.message.PlatformMessageEvent;

public interface IPlatformEventPublisher {

    void publish(PlatformMessageEvent event);
}