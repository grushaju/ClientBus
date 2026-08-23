package kit.penny.clientbus.server.mapper;

import kit.penny.clientbus.common.dto.message.MessageAttachmentDto;
import kit.penny.clientbus.server.persistence.entity.MessageAttachmentEntity;
import org.springframework.stereotype.Component;

@Component
public class MessageAttachmentMapper {

    public MessageAttachmentDto toDto(
            MessageAttachmentEntity entity
    ) {

        return new MessageAttachmentDto(
                entity.getId(),
                entity.getMessage().getId(),
                entity.getType(),
                entity.getFileName(),
                entity.getContentType(),
                entity.getSize(),
                entity.getStorageKey(),
                entity.getCreatedAt()
        );
    }
}