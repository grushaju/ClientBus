package kit.penny.clientbus.common.dto.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.MessageType;

import java.time.Instant;
import java.util.UUID;

public record InboundMessageRequest(

        @NotNull
        UUID channelAccountId,

        @NotBlank
        String clientExternalId,

        String clientUsername,

        String clientPhone,

        String clientDisplayName,

        @NotBlank
        String externalId,

        @NotNull
        MessageType type,

        String content,

        String metadata,

        Instant sentAt

) {
}