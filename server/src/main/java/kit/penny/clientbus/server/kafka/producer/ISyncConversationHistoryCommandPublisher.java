package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;

import java.util.UUID;

public interface ISyncConversationHistoryCommandPublisher {

    void publish(
            ChannelType channelType,
            SyncConversationHistoryCommand command
    );

    void publish(
            ChannelType channelType,
            SyncConversationHistoryCommand command,
            UUID correlationId
    );
}