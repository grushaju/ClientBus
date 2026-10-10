
package kit.penny.clientbus.server.connector.command;

import java.util.UUID;

public record SyncClientAccountCommand(
        UUID channelAccountId,
        UUID clientAccountId,
        String externalId
) implements ChannelConnectorCommand {
}
