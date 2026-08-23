package kit.penny.clientbus.common.dto.message;

import kit.penny.clientbus.common.enums.MessageAttachmentType;

import java.time.Instant;
import java.util.UUID;

public record MessageAttachmentDto(

        UUID id,

        UUID messageId,

        MessageAttachmentType type,

        String fileName,

        String contentType,

        long size,

        String storageKey,

        Instant createdAt

) {
}