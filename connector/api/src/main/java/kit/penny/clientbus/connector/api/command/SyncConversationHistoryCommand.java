package kit.penny.clientbus.connector.api.command;

import java.util.UUID;

public record SyncConversationHistoryCommand(
        UUID channelAccountId,
        String conversationExternalId,
        String beforeExternalId,
        int limit,

        /**
         * 13.2b: external dialog reference when the platform addresses dialogs separately
         * from their counterpart; nullable — connectors resolve their own dialog otherwise.
         */
        String dialogExternalId
) implements ChannelConnectorCommand {

    public SyncConversationHistoryCommand(
            UUID channelAccountId,
            String conversationExternalId,
            String beforeExternalId,
            int limit
    ) {
        this(channelAccountId, conversationExternalId, beforeExternalId, limit, null);
    }
}
