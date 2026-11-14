package kit.penny.clientbus.connector.api.command;

import java.util.UUID;

public record ReadClientAccountAvatarCommand(
        UUID channelAccountId,
        String externalId
) implements ChannelConnectorCommand {
}
