package kit.penny.clientbus.server.connector.command;

import java.util.UUID;

public record SyncConversationHistoryCommand(
        UUID channelAccountId,
        String conversationExternalId,
        String beforeExternalId,
        int limit
) implements ChannelConnectorCommand {
}