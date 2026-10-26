package kit.penny.clientbus.server.connector.command;

import java.util.UUID;

public record DiscoverClientAccountCommand(
        UUID channelAccountId,
        String phone,
        String username
) implements ChannelConnectorCommand {
}
