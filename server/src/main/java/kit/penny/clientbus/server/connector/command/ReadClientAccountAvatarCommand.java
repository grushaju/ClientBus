package kit.penny.clientbus.server.connector.command;

import java.util.UUID;

public record ReadClientAccountAvatarCommand(
        UUID channelAccountId,
        String externalId
) implements ChannelConnectorCommand {
}
