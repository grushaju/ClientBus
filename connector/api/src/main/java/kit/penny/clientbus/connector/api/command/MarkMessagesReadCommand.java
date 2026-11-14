package kit.penny.clientbus.connector.api.command;

import java.util.UUID;

public record MarkMessagesReadCommand(
        UUID channelAccountId,
        String recipientExternalId,
        String lastReadExternalId
) implements ChannelConnectorCommand {
}
