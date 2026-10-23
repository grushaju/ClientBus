package kit.penny.clientbus.server.connector.command;

import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.service.ChannelAttachment;

import java.util.List;
import java.util.UUID;

public record SendMessageCommand(
        UUID messageId,
        UUID channelAccountId,
        String recipientExternalId,
        MessageType type,
        String content,
        List<ChannelAttachment> attachments,
        UUID sendAttemptId
) implements ChannelConnectorCommand {

    public SendMessageCommand(UUID messageId, UUID channelAccountId, String recipientExternalId,
            MessageType type, String content, List<ChannelAttachment> attachments) {
        this(messageId, channelAccountId, recipientExternalId, type, content, attachments, null);
    }

    public SendMessageCommand {
        attachments =
                attachments == null
                        ? List.of()
                        : List.copyOf(attachments);
    }
}
