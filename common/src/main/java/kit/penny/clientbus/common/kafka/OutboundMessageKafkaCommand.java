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

        List<PlatformOutboundAttachment> attachments,

        UUID sendAttemptId,

        /** 13.2b: внешняя ссылка диалога платформы; nullable для старых payload. */
        String dialogExternalId

) {
    public OutboundMessageKafkaCommand(UUID messageId, UUID channelAccountId, String recipientExternalId,
            MessageType type, String content, List<PlatformOutboundAttachment> attachments) {
        this(messageId, channelAccountId, recipientExternalId, type, content, attachments, null, null);
    }

    public OutboundMessageKafkaCommand(UUID messageId, UUID channelAccountId, String recipientExternalId,
            MessageType type, String content, List<PlatformOutboundAttachment> attachments, UUID sendAttemptId) {
        this(messageId, channelAccountId, recipientExternalId, type, content, attachments, sendAttemptId, null);
    }
}
