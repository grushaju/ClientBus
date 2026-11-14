package kit.penny.clientbus.connector.api.model;

import java.time.Instant;
import java.util.UUID;

/** Neutral connector payload mirroring the host conversation request field-for-field. */
public record ConnectorConversation(

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

    public ConnectorConversation(
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
                null
        );
    }

    public ConnectorConversation {

        if (unreadCount < 0) {
            throw new IllegalArgumentException(
                    "unreadCount must not be negative"
            );
        }
    }
}
