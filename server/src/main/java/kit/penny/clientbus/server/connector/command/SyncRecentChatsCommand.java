package kit.penny.clientbus.server.connector.command;

import java.util.UUID;

public record SyncRecentChatsCommand(
        UUID channelAccountId
) implements ChannelConnectorCommand {
}