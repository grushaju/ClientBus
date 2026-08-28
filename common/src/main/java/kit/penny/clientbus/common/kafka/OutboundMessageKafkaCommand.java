package kit.penny.clientbus.common.kafka;

import kit.penny.clientbus.common.enums.MessageType;

import java.util.List;
import java.util.UUID;

public record OutboundMessageKafkaCommand(

        UUID messageId,

        UUID channelAccountId,

        String recipientExternalId,

        MessageType type,

        String content,

        List<PlatformOutboundAttachment> attachments

) {
}