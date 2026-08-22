package kit.penny.clientbus.common.dto.message;

import jakarta.validation.constraints.NotNull;
import kit.penny.clientbus.common.enums.MessageType;

import java.util.UUID;

public record CreateOutboundMessageRequest(

        @NotNull
        UUID conversationId,

        @NotNull
        MessageType type,

        String content,

        String metadata

) {
}