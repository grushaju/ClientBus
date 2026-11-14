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

        int unreadCount,

        String avatarUrl,

        /** 13.2b: внешняя ссылка диалога платформы; nullable для старых payload. */
        String dialogExternalId

) {

    public PlatformConversationRequest(
            UUID channelAccountId,
            String clientExternalId,
            String clientUsername,
            String clientPhone,
            String clientDisplayName,
            Instant lastMessageAt,
            String lastMessagePreview,
            int unreadCount
    ) {
        this(
                channelAccountId,
                clientExternalId,
                clientUsername,
                clientPhone,
                clientDisplayName,
                lastMessageAt,
                lastMessagePreview,
                unreadCount,
                null,
                null
        );
    }

    public PlatformConversationRequest(
            UUID channelAccountId,
            String clientExternalId,
            String clientUsername,
            String clientPhone,
            String clientDisplayName,
            Instant lastMessageAt,
            String lastMessagePreview,
            int unreadCount,
            String avatarUrl
    ) {
        this(
                channelAccountId,
                clientExternalId,
                clientUsername,
                clientPhone,
                clientDisplayName,
                lastMessageAt,
                lastMessagePreview,
                unreadCount,
                avatarUrl,
                null
        );
    }

    public PlatformConversationRequest {

        if (unreadCount < 0) {
            throw new IllegalArgumentException(
                    "unreadCount must not be negative"
            );
        }
    }
}