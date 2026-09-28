package kit.penny.clientbus.common.dto.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.MessageType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlatformMessageRequest(

        @NotNull
        UUID channelAccountId,

        @NotBlank
        String clientExternalId,

        String clientUsername,

        String clientPhone,

        String clientDisplayName,

        @NotBlank
        String senderExternalId,

        @NotBlank
        String externalId,

        @NotNull
        MessageType type,

        String content,

        String metadata,

        Instant sentAt,

        List<PlatformMessageAttachment> attachments

) {
}