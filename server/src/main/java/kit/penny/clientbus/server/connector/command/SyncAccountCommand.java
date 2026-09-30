package kit.penny.clientbus.server.connector.command;

import java.util.UUID;

public record SyncAccountCommand(
        UUID channelAccountId
) implements ChannelConnectorCommand {
}