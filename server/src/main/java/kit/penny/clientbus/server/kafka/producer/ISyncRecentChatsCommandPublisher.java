package kit.penny.clientbus.server.kafka.producer;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.connector.api.command.SyncRecentChatsCommand;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface ISyncRecentChatsCommandPublisher {

    CompletableFuture<Void> publish(
            ChannelType channelType,
            SyncRecentChatsCommand command,
            UUID syncRunId
    );
}