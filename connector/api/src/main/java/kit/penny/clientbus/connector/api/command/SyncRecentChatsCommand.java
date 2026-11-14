package kit.penny.clientbus.connector.api.command;

import java.util.UUID;

public record SyncRecentChatsCommand(
        UUID channelAccountId
) implements ChannelConnectorCommand {
}
