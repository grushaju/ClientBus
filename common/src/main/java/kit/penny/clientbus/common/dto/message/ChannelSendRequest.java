package kit.penny.clientbus.common.dto.message;

import kit.penny.clientbus.common.enums.MessageType;

import java.util.List;
import java.util.UUID;

public record ChannelSendRequest(

        UUID messageId,

        UUID channelAccountId,

        String recipientExternalId,

        MessageType type,

        String content,

        List<MessageAttachmentRequest> attachments

) {
}
