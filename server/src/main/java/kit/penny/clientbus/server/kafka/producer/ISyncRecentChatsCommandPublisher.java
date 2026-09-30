package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.server.connector.command.SyncRecentChatsCommand;

public interface ISyncRecentChatsCommandPublisher {

    void publish(
            ChannelType channelType,
            SyncRecentChatsCommand command
    );
}