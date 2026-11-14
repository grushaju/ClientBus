package kit.penny.clientbus.connector.api.command;

import java.util.UUID;

public record MarkMessagesReadCommand(
        UUID channelAccountId,
        String recipientExternalId,
        String lastReadExternalId,

        /** 13.2b: external dialog reference; nullable for legacy counterpart-only payloads. */
        String dialogExternalId
) implements ChannelConnectorCommand {

    public MarkMessagesReadCommand(
            UUID channelAccountId,
            String recipientExternalId,
            String lastReadExternalId
    ) {
        this(channelAccountId, recipientExternalId, lastReadExternalId, null);
    }
}
