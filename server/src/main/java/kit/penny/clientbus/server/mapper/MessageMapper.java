package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.message.MessageDto;
import kit.penny.clientbus.server.persistence.entity.MessageEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MessageMapper {

    public MessageDto toDto(MessageEntity entity) {

        UUID conversationId =
                entity.getConversation() != null
                        ? entity.getConversation().getId()
                        : null;

        UUID clientAccountId =
                entity.getClientAccount() != null
                        ? entity.getClientAccount().getId()
                        : null;

        UUID employeeId =
                entity.getEmployee() != null
                        ? entity.getEmployee().getId()
                        : null;

        return new MessageDto(
                entity.getId(),
                conversationId,
                entity.getType(),
                entity.getDirection(),
                entity.getSenderType(),
                clientAccountId,
                employeeId,
                entity.getExternalId(),
                entity.getContent(),
                entity.getMetadata(),
                entity.getSentAt(),
                entity.getCreatedAt(),
                entity.getProcessingStatus(),
                entity.getDeliveryStatus(),
                entity.getProcessedAt(),
                entity.getDeliveredAt(),
                entity.getReadAt()
        );
    }
}