package kit.penny.clientbus.common.dto.message;

import kit.penny.clientbus.common.enums.MessageDeliveryStatus;
import kit.penny.clientbus.common.enums.MessageDirection;
import kit.penny.clientbus.common.enums.MessageProcessingStatus;
import kit.penny.clientbus.common.enums.MessageSenderType;
import kit.penny.clientbus.common.enums.MessageType;

import java.time.Instant;
import java.util.UUID;

public record MessageDto(

        UUID id,

        UUID conversationId,

        MessageType type,

        MessageDirection direction,

        MessageSenderType senderType,

        UUID clientAccountId,

        UUID employeeId,

        String externalId,

        String content,

        String metadata,

        Instant sentAt,

        Instant createdAt,

        MessageProcessingStatus processingStatus,

        MessageDeliveryStatus deliveryStatus,

        Instant processedAt,

        Instant deliveredAt,

        Instant readAt

) {
}