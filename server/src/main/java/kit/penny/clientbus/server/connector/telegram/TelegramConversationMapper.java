package kit.penny.clientbus.server.connector.telegram;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import org.drinkless.tdlib.TdApi;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class TelegramConversationMapper {

    public PlatformConversationRequest map(
            UUID channelAccountId,
            TdApi.Chat chat,
            TdApi.User user
    ) {

        if (channelAccountId == null) {
            throw new IllegalArgumentException(
                    "channelAccountId must not be null"
            );
        }

        if (chat == null) {
            throw new IllegalArgumentException(
                    "Telegram chat must not be null"
            );
        }

        if (user == null) {
            throw new IllegalArgumentException(
                    "Telegram user must not be null"
            );
        }

        return new PlatformConversationRequest(
                channelAccountId,
                Long.toString(user.id),
                extractUsername(user),
                user.phoneNumber,
                buildDisplayName(
                        user.firstName,
                        user.lastName
                ),
                extractLastMessageAt(
                        chat.lastMessage
                ),
                extractLastMessagePreview(
                        chat.lastMessage
                ),
                Math.max(0, chat.unreadCount)
        );
    }

    private Instant extractLastMessageAt(
            TdApi.Message message
    ) {

        if (message == null) {
            return null;
        }

        return Instant.ofEpochSecond(
                message.date
        );
    }

    private String extractLastMessagePreview(
            TdApi.Message message
    ) {

        if (message == null
                || message.content == null) {
            return null;
        }

        switch (message.content) {
            case TdApi.MessageText messageText -> {

                if (messageText.text == null) {
                    return null;
                }

                return messageText.text.text;
            }
            case TdApi.MessagePhoto messagePhoto -> {

                return extractCaption(
                        messagePhoto.caption
                );
            }
            case TdApi.MessageAudio messageAudio -> {

                return extractCaption(
                        messageAudio.caption
                );
            }
            default -> {
            }
        }

        return null;
    }

    private String extractCaption(
            TdApi.FormattedText text
    ) {

        if (text == null) {
            return null;
        }

        return text.text;
    }

    private String extractUsername(
            TdApi.User user
    ) {

        if (user.usernames == null
                || user.usernames.activeUsernames == null
                || user.usernames.activeUsernames.length == 0) {
            return null;
        }

        return user.usernames.activeUsernames[0];
    }

    private String buildDisplayName(
            String firstName,
            String lastName
    ) {

        String first =
                firstName == null
                        ? ""
                        : firstName.trim();

        String last =
                lastName == null
                        ? ""
                        : lastName.trim();

        if (first.isEmpty()) {
            return last.isEmpty()
                    ? null
                    : last;
        }

        if (last.isEmpty()) {
            return first;
        }

        return first + " " + last;
    }
}
