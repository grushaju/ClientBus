package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;

public interface ISyncConversationHistoryCommandPublisher {

    void publish(
            ChannelType channelType,
            SyncConversationHistoryCommand command
    );
}