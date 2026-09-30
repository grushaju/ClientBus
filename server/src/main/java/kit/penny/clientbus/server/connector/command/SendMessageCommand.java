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
        List<ChannelAttachment> attachments
) implements ChannelConnectorCommand {

    public SendMessageCommand {
        attachments =
                attachments == null
                        ? List.of()
                        : List.copyOf(attachments);
    }
}