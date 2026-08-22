package kit.penny.clientbus.common.dto.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.MessageType;

import java.time.Instant;
import java.util.UUID;

public record CreateInboundMessageRequest(

        @NotNull
        UUID conversationId,

        @NotNull
        MessageType type,

        @NotBlank
        String externalId,

        String content,

        String metadata,

        Instant sentAt

) {
}