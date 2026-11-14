package kit.penny.clientbus.connector.api.command;

import java.util.UUID;

public record DiscoverClientAccountCommand(
        UUID channelAccountId,
        String phone,
        String username
) implements ChannelConnectorCommand {
}
