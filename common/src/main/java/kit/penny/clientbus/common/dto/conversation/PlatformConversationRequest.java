package kit.penny.clientbus.common.dto.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record PlatformConversationRequest(

        @NotNull
        UUID channelAccountId,

        @NotBlank
        String clientExternalId,

        String clientUsername,

        String clientPhone,

        String clientDisplayName,

        Instant lastMessageAt,

        String lastMessagePreview,

        int unreadCount

) {

    public PlatformConversationRequest {

        if (unreadCount < 0) {
            throw new IllegalArgumentException(
                    "unreadCount must not be negative"
            );
        }
    }
}