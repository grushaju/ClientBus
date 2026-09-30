package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;

public interface IPlatformConversationPublisher {

    void publish(
            PlatformConversationRequest conversation
    );
}