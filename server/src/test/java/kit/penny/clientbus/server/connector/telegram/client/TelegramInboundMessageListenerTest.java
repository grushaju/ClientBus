package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.service.MessageProcessingService;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.query.TdlibResponse;
import kit.penny.tdlib.service.TelegramUserService;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramInboundMessageListenerTest {

    private static final UUID CHANNEL_ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final long CHAT_ID = 100L;
    private static final long USER_ID = 200L;
    private static final long MESSAGE_ID = 300L;

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private ObjectProvider<TelegramClient> telegramClientProvider;

    @Mock
    private MessageProcessingService messageProcessingService;

    @Mock
    private TelegramUserService telegramUserService;

    private TelegramInboundMessageListener listener;

    @BeforeEach
    void setUp() {
        listener = new TelegramInboundMessageListener(
                CHANNEL_ACCOUNT_ID,
                telegramClientProvider,
                telegramUserService,
                messageProcessingService
        );
    }

    @Test
    void shouldProcessInboundPrivateTextMessage() {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = createTextMessage(
                false,
                new TdApi.MessageSenderUser(USER_ID),
                "Hello"
        );

        TdApi.Chat chat = createPrivateChat(CHAT_ID);

        mockGetChatSuccess(chat);

        TdApi.User user = new TdApi.User();
        user.id = USER_ID;
        user.firstName = "Ivan";
        user.lastName = "Ivanov";
        user.phoneNumber = "+79991234567";


        TdlibResponse<TdApi.User> userResponse =
                new TdlibResponse<>(user, null);
        when(telegramUserService.getUser(USER_ID))
                .thenReturn(
                        CompletableFuture.completedFuture(userResponse)
                );

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        ArgumentCaptor<InboundMessageRequest> requestCaptor =
                ArgumentCaptor.forClass(InboundMessageRequest.class);

        verify(messageProcessingService).processInbound(
                requestCaptor.capture(),
                any(List.class)
        );

        InboundMessageRequest request = requestCaptor.getValue();

        assertEquals(CHANNEL_ACCOUNT_ID, request.channelAccountId());
        assertEquals(Long.toString(USER_ID), request.clientExternalId());
        assertEquals(Long.toString(MESSAGE_ID), request.externalId());
        assertEquals(MessageType.TEXT, request.type());
        assertEquals("Hello", request.content());
        assertEquals("+79991234567", request.clientPhone());
        assertEquals("Ivan Ivanov", request.clientDisplayName());
        assertEquals(Instant.ofEpochSecond(1_700_000_000), request.sentAt());

        verify(telegramUserService)
                .getUser(USER_ID);
    }

    @Test
    void shouldIgnoreOutgoingMessage() {
        TdApi.Message message = createTextMessage(
                true,
                new TdApi.MessageSenderUser(USER_ID),
                "Hello"
        );

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(messageProcessingService, never())
                .processInbound(any(), any());

        verify(telegramClient, never())
                .sendAsync(any());
    }

    @Test
    void shouldIgnoreMessageFromUnsupportedSender() {
        TdApi.Message message = createTextMessage(
                false,
                new TdApi.MessageSenderChat(CHAT_ID),
                "Hello"
        );

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(messageProcessingService, never())
                .processInbound(any(), any());

        verify(telegramClient, never())
                .sendAsync(any());
    }

    @Test
    void shouldIgnoreNonTextMessage() {
        TdApi.Message message = new TdApi.Message();
        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = false;
        message.senderId = new TdApi.MessageSenderUser(USER_ID);
        message.content = new TdApi.MessagePhoto();

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(messageProcessingService, never())
                .processInbound(any(), any());

        verify(telegramClient, never())
                .sendAsync(any());
    }

    @Test
    void shouldIgnoreMessageFromNonPrivateChat() {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = createTextMessage(
                false,
                new TdApi.MessageSenderUser(USER_ID),
                "Hello"
        );

        TdApi.Chat chat = new TdApi.Chat();
        chat.id = CHAT_ID;
        chat.type = new TdApi.ChatTypeBasicGroup(500L);

        mockGetChatSuccess(chat);

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(messageProcessingService, never())
                .processInbound(any(), any());
    }

    @Test
    void shouldNotProcessMessageWhenGetChatFails() {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = createTextMessage(
                false,
                new TdApi.MessageSenderUser(USER_ID),
                "Hello"
        );

        TdApi.Error error =
                new TdApi.Error(400, "Chat not found");

        mockGetChatError(error);

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(messageProcessingService, never())
                .processInbound(any(), any());
    }

    @Test
    void shouldNotProcessMessageWhenChatResponseIsEmpty() {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = createTextMessage(
                false,
                new TdApi.MessageSenderUser(USER_ID),
                "Hello"
        );

        mockGetChatSuccess(null);

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(messageProcessingService, never())
                .processInbound(any(), any());
    }

    @Test
    void shouldReturnUpdateNewMessageAsNotificationType() {
        assertEquals(
                TdApi.UpdateNewMessage.class,
                listener.notificationType()
        );
    }

    private TdApi.Message createTextMessage(
            boolean outgoing,
            TdApi.MessageSender sender,
            String text
    ) {
        TdApi.Message message = new TdApi.Message();

        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = outgoing;
        message.senderId = sender;
        message.date = 1_700_000_000;
        message.content =
                new TdApi.MessageText(
                        new TdApi.FormattedText(
                                text,
                                new TdApi.TextEntity[1]
                        ),
                        new TdApi.LinkPreview(),
                        new TdApi.LinkPreviewOptions()
                );

        return message;
    }

    private TdApi.Chat createPrivateChat(long chatId) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = chatId;
        chat.type = new TdApi.ChatTypePrivate(USER_ID);
        return chat;
    }

    private void mockGetChatSuccess(TdApi.Chat chat) {
        TdlibResponse<TdApi.Chat> response =
                new TdlibResponse<>(chat, null);

        doReturn(
                CompletableFuture.completedFuture(response)
        ).when(telegramClient).sendAsync(any());
    }

    private void mockGetChatError(TdApi.Error error) {
        TdlibResponse<TdApi.Chat> response =
                new TdlibResponse<>(null, error);

        doReturn(
                CompletableFuture.completedFuture(response)
        ).when(telegramClient).sendAsync(any());
    }
}