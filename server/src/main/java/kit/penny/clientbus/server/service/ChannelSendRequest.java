package kit.penny.clientbus.server.service;

import kit.penny.clientbus.common.enums.MessageType;

import java.util.List;
import java.util.UUID;

public record ChannelSendRequest(

        UUID messageId,

        UUID channelAccountId,

        String recipientExternalId,

        MessageType type,

        String content,

        List<ChannelAttachment> attachments

) {

    public ChannelSendRequest {
        attachments = attachments == null
                ? List.of()
                : List.copyOf(attachments);
    }
}