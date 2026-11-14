package kit.penny.clientbus.connector.api.command;

import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.connector.api.model.ConnectorAttachment;

import java.util.List;
import java.util.UUID;

public record SendMessageCommand(
        UUID messageId,
        UUID channelAccountId,
        String recipientExternalId,
        MessageType type,
        String content,
        List<ConnectorAttachment> attachments,
        UUID sendAttemptId
) implements ChannelConnectorCommand {

    public SendMessageCommand(UUID messageId, UUID channelAccountId, String recipientExternalId,
            MessageType type, String content, List<ConnectorAttachment> attachments) {
        this(messageId, channelAccountId, recipientExternalId, type, content, attachments, null);
    }

    public SendMessageCommand {
        attachments =
                attachments == null
                        ? List.of()
                        : List.copyOf(attachments);
    }
}
