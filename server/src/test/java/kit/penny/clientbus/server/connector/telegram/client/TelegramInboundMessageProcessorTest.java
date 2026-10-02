package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.query.TdlibResponse;
import kit.penny.tdlib.service.TelegramUserService;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramInboundMessageProcessorTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final long CHAT_ID = 100L;
    private static final long USER_ID = 200L;
    private static final long MESSAGE_ID = 300L;

    @Mock
    private ObjectProvider<TelegramClient> clientProvider;

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private TelegramUserService telegramUserService;

    @Mock
    private IAttachmentStorage attachmentStorage;

    private TelegramInboundMessageProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TelegramInboundMessageProcessor(
                clientProvider,
                telegramUserService,
                attachmentStorage
        );

    }

    @Test
    void shouldMapPrivateTextMessage() {
        TdApi.Message message = textMessage("Hello");

        when(clientProvider.getObject())
                .thenReturn(telegramClient);

        when(telegramClient.sendAsync(any(TdApi.Function.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        new TdlibResponse<>(privateChat(USER_ID), null)
                ));

        when(telegramUserService.getUser(USER_ID))
                .thenReturn(CompletableFuture.completedFuture(
                        new TdlibResponse<>(user(USER_ID), null)
                ));

        PlatformMessageRequest result =
                processor.process(ACCOUNT_ID, message).join();

        assertNotNull(result);
        assertEquals(ACCOUNT_ID, result.channelAccountId());
        assertEquals(Long.toString(USER_ID), result.clientExternalId());
        assertEquals("john_doe", result.clientUsername());
        assertEquals("+491234567", result.clientPhone());
        assertEquals("John Doe", result.clientDisplayName());
        assertEquals(Long.toString(USER_ID), result.senderExternalId());
        assertEquals(Long.toString(MESSAGE_ID), result.externalId());
        assertEquals(MessageType.TEXT, result.type());
        assertEquals("Hello", result.content());
        assertEquals(
                Instant.ofEpochSecond(1_700_000_000),
                result.sentAt()
        );
        assertNotNull(result.attachments());
        assertTrue(result.attachments().isEmpty());

        verify(telegramUserService).getUser(USER_ID);
    }

    @Test
    void shouldIgnoreGroupChat() {
        TdApi.Chat chat = new TdApi.Chat();
        chat.type = new TdApi.ChatTypeBasicGroup(500L);

        when(clientProvider.getObject())
                .thenReturn(telegramClient);

        when(telegramClient.sendAsync(any(TdApi.Function.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        new TdlibResponse<>(chat, null)
                ));


        PlatformMessageRequest result =
                processor.process(ACCOUNT_ID, textMessage("Group message"))
                        .join();

        assertNull(result);
        verifyNoInteractions(telegramUserService);
    }

    @Test
    void shouldIgnoreUnsupportedMessageContent() {
        TdApi.Message message = textMessage("Ignored");
        message.content = new TdApi.MessageSticker(
                new TdApi.Sticker(),
                false
        );

        when(clientProvider.getObject())
                .thenReturn(telegramClient);

        when(telegramClient.sendAsync(any(TdApi.Function.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        new TdlibResponse<>(privateChat(USER_ID), null)
                ));

        when(telegramUserService.getUser(USER_ID))
                .thenReturn(CompletableFuture.completedFuture(
                        new TdlibResponse<>(user(USER_ID), null)
                ));

        PlatformMessageRequest result =
                processor.process(ACCOUNT_ID, message).join();

        assertNull(result);
    }

    @Test
    void shouldFailWhenChannelAccountIdIsNull() {
        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> processor.process(null, textMessage("Hello")).join()
        );

        assertInstanceOf(
                IllegalArgumentException.class,
                exception.getCause()
        );
    }

    @Test
    void shouldFailWhenMessageIsNull() {
        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> processor.process(ACCOUNT_ID, null).join()
        );

        assertInstanceOf(
                IllegalArgumentException.class,
                exception.getCause()
        );
    }


    @Test
    void shouldPropagateTelegramChatLookupFailure() {
        when(clientProvider.getObject()).thenReturn(telegramClient);

        when(telegramClient.sendAsync(any(TdApi.Function.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("TDLib unavailable")
                ));

        assertThrows(
                CompletionException.class,
                () -> processor.process(
                        ACCOUNT_ID,
                        textMessage("Hello")
                ).join()
        );
    }


    private static TdApi.Message textMessage(String text) {
        TdApi.Message message = new TdApi.Message();
        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.date = 1_700_000_000;
        message.senderId = new TdApi.MessageSenderUser(USER_ID);
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

    private static TdApi.Chat privateChat(long userId) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = CHAT_ID;
        chat.type = new TdApi.ChatTypePrivate(userId);
        return chat;
    }

    private static TdApi.User user(long id) {
        TdApi.User user = new TdApi.User();
        user.id = id;
        user.firstName = "John";
        user.lastName = "Doe";
        user.phoneNumber = "+491234567";
        user.usernames = new TdApi.Usernames(
                new String[]{"john_doe"},
                new String[0],
                "john_doe",
                new String[0]
        );
        return user;
    }
}
