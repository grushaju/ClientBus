package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.conversation.ConversationDto;
import kit.penny.clientbus.server.persistence.entity.ConversationEntity;
import org.springframework.stereotype.Component;

@Component
public class ConversationMapper {

    public ConversationDto toDto(
            ConversationEntity entity
    ) {

        if (entity == null) {
            return null;
        }

        return new ConversationDto(
                entity.getId(),

                entity.getWorkspace().getId(),

                entity.getChannelAccount().getId(),

                entity.getClientAccount().getId(),

                entity.getAssignedEmployee() != null
                        ? entity.getAssignedEmployee().getId()
                        : null,

                entity.getLastMessageAt(),

                entity.getLastMessagePreview(),

                entity.getUnreadCount(),

                entity.getCreatedAt(),

                entity.getUpdatedAt()
        );
    }
}