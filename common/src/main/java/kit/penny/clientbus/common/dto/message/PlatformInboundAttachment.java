package kit.penny.clientbus.common.dto.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.MessageAttachmentType;

public record PlatformInboundAttachment(

        @NotNull
        MessageAttachmentType type,

        @NotBlank
        String storageKey,

        String fileName,

        String contentType,

        long size

) {
}