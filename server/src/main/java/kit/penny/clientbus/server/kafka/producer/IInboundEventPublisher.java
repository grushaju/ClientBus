package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.dto.message.PlatformInboundMessageEvent;

public interface IInboundEventPublisher {

    void publish(PlatformInboundMessageEvent event);
}