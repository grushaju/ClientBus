package kit.penny.clientbus.server.connector.telegram;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TelegramConversationMapperTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private TelegramConversationMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TelegramConversationMapper();
    }

    @Test
    void shouldMapPrivateChatAndUser() {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = 100L;
        chat.unreadCount = 7;
        chat.lastMessage = textMessage(900L, 1_700_000_000, "Latest");

        TdApi.User user = user(200L, "John", "Doe");

        PlatformConversationRequest result =
                mapper.map(ACCOUNT_ID, chat, user);

        assertEquals(ACCOUNT_ID, result.channelAccountId());
        assertEquals("200", result.clientExternalId());
        assertEquals("john_doe", result.clientUsername());
        assertEquals("+491234567", result.clientPhone());
        assertEquals("John Doe", result.clientDisplayName());
        assertEquals(
                Instant.ofEpochSecond(1_700_000_000),
                result.lastMessageAt()
        );
        assertEquals("Latest", result.lastMessagePreview());
        assertEquals(7, result.unreadCount());
    }

    @Test
    void shouldTrimDisplayName() {
        TdApi.Chat chat = new TdApi.Chat();
        TdApi.User user = user(200L, " John ", " Doe ");

        PlatformConversationRequest result =
                mapper.map(ACCOUNT_ID, chat, user);

        assertEquals("John Doe", result.clientDisplayName());
    }

    @Test
    void shouldHandleMissingLastMessage() {
        TdApi.Chat chat = new TdApi.Chat();
        chat.lastMessage = null;

        PlatformConversationRequest result =
                mapper.map(ACCOUNT_ID, chat, user(200L, "John", ""));

        assertNull(result.lastMessageAt());
        assertNull(result.lastMessagePreview());
    }

    @Test
    void shouldClampNegativeUnreadCountToZero() {
        TdApi.Chat chat = new TdApi.Chat();
        chat.unreadCount = -1;

        PlatformConversationRequest result =
                mapper.map(ACCOUNT_ID, chat, user(200L, "John", ""));

        assertEquals(0, result.unreadCount());
    }

    @Test
    void shouldMapPhotoCaptionAsLastMessagePreview() {
        TdApi.Chat chat = new TdApi.Chat();

        TdApi.Message message = new TdApi.Message();
        message.date = 1_700_000_000;
        message.content = new TdApi.MessagePhoto(
                null,
                null,
                new TdApi.FormattedText(
                        "Photo caption",
                        new TdApi.TextEntity[0]
                ),
                false,
                false,
                false
        );

        chat.lastMessage = message;

        PlatformConversationRequest result =
                mapper.map(ACCOUNT_ID, chat, user(200L, "John", ""));

        assertEquals("Photo caption", result.lastMessagePreview());
    }

    @Test
    void shouldRejectNullAccountId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.map(null, new TdApi.Chat(), user(200L, "John", ""))
        );
    }

    @Test
    void shouldRejectNullChat() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.map(ACCOUNT_ID, null, user(200L, "John", ""))
        );
    }

    @Test
    void shouldRejectNullUser() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mapper.map(ACCOUNT_ID, new TdApi.Chat(), null)
        );
    }

    private static TdApi.User user(
            long id,
            String firstName,
            String lastName
    ) {
        TdApi.User user = new TdApi.User();
        user.id = id;
        user.firstName = firstName;
        user.lastName = lastName;
        user.phoneNumber = "+491234567";
        user.usernames = new TdApi.Usernames(
                new String[]{"john_doe"},
                new String[0],
                "john_doe",
                new String[0]
        );
        return user;
    }

    private static TdApi.Message textMessage(
            long id,
            int date,
            String text
    ) {
        TdApi.Message message = new TdApi.Message();
        message.id = id;
        message.date = date;
        message.content = new TdApi.MessageText(
                new TdApi.FormattedText(
                        text,
                        new TdApi.TextEntity[0]
                ),
                null,
                null
        );
        return message;
    }
}
