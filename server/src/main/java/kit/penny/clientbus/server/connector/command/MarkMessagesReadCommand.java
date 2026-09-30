package kit.penny.clientbus.server.connector.command;

import java.util.UUID;

public record MarkMessagesReadCommand(
        UUID channelAccountId,
        String recipientExternalId,
        String lastReadExternalId
) implements ChannelConnectorCommand {
}