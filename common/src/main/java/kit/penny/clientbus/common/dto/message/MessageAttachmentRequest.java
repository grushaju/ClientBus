package kit.penny.clientbus.common.dto.message;

import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.MessageAttachmentType;

import java.util.UUID;

public record MessageAttachmentRequest(

        /**
         * Тип attachment.
         */
        @NotNull
        MessageAttachmentType type,

        /**
         * ID уже созданного MessageAttachment.
         */
        @NotNull
        UUID attachmentId

) {
}