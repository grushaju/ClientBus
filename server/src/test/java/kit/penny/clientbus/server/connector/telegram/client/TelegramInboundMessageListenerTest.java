package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramInboundMessageListenerTest {

    private static final UUID CHANNEL_ACCOUNT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final long CHAT_ID = 100L;
    private static final long USER_ID = 200L;
    private static final long MESSAGE_ID = 300L;

    @Mock
    private TelegramInboundMessageProcessor messageProcessor;

    private TelegramInboundMessageListener listener;

    @BeforeEach
    void setUp() {
        listener =
                new TelegramInboundMessageListener(
                        CHANNEL_ACCOUNT_ID,
                        messageProcessor
                );
    }

    @Test
    void shouldProcessInboundMessage() {

        TdApi.Message message =
                createTextMessage(
                        false,
                        new TdApi.MessageSenderUser(USER_ID),
                        "Hello"
                );

        when(messageProcessor.process(
                CHANNEL_ACCOUNT_ID,
                message
        )).thenReturn(
                CompletableFuture.completedFuture(null)
        );

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(messageProcessor)
                .process(
                        CHANNEL_ACCOUNT_ID,
                        message
                );
    }

    @Test
    void shouldIgnoreOutgoingMessageWithSendingState() {

        TdApi.Message message =
                createTextMessage(
                        true,
                        new TdApi.MessageSenderUser(USER_ID),
                        "Hello"
                );

        message.sendingState =
                new TdApi.MessageSendingStatePending();

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verifyNoInteractions(messageProcessor);
    }

    @Test
    void shouldProcessOutgoingMessageWithoutSendingState() {

        TdApi.Message message =
                createTextMessage(
                        true,
                        new TdApi.MessageSenderUser(USER_ID),
                        "Hello from another Telegram device"
                );

        message.sendingState = null;

        when(messageProcessor.process(
                CHANNEL_ACCOUNT_ID,
                message
        )).thenReturn(
                CompletableFuture.completedFuture(null)
        );

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(messageProcessor)
                .process(
                        CHANNEL_ACCOUNT_ID,
                        message
                );
    }

    @Test
    void shouldIgnoreMessageFromUnsupportedSender() {

        TdApi.Message message =
                createTextMessage(
                        false,
                        new TdApi.MessageSenderChat(CHAT_ID),
                        "Hello"
                );

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verifyNoInteractions(messageProcessor);
    }

    @Test
    void shouldIgnoreNullNotification() {

        listener.handleNotification(null);

        verifyNoInteractions(messageProcessor);
    }

    @Test
    void shouldIgnoreNotificationWithNullMessage() {

        TdApi.UpdateNewMessage update =
                new TdApi.UpdateNewMessage(null);

        listener.handleNotification(update);

        verifyNoInteractions(messageProcessor);
    }

    @Test
    void shouldReturnUpdateNewMessageAsNotificationType() {

        assertEquals(
                TdApi.UpdateNewMessage.class,
                listener.notificationType()
        );
    }

    @Test
    void shouldKeepChannelAccountIdWhenDelegating() {

        TdApi.Message message =
                createTextMessage(
                        false,
                        new TdApi.MessageSenderUser(USER_ID),
                        "Hello"
                );

        when(messageProcessor.process(
                CHANNEL_ACCOUNT_ID,
                message
        )).thenReturn(
                CompletableFuture.completedFuture(null)
        );

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        ArgumentCaptor<UUID> accountCaptor =
                ArgumentCaptor.forClass(UUID.class);

        ArgumentCaptor<TdApi.Message> messageCaptor =
                ArgumentCaptor.forClass(TdApi.Message.class);

        verify(messageProcessor)
                .process(
                        accountCaptor.capture(),
                        messageCaptor.capture()
                );

        assertEquals(
                CHANNEL_ACCOUNT_ID,
                accountCaptor.getValue()
        );

        assertEquals(
                message,
                messageCaptor.getValue()
        );
    }

    @Test
    void shouldNotWaitForProcessorResult() {

        TdApi.Message message =
                createTextMessage(
                        false,
                        new TdApi.MessageSenderUser(USER_ID),
                        "Hello"
                );

        CompletableFuture<PlatformMessageRequest> future =
                new CompletableFuture<>();

        when(messageProcessor.process(
                CHANNEL_ACCOUNT_ID,
                message
        )).thenReturn(future);

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(messageProcessor)
                .process(
                        CHANNEL_ACCOUNT_ID,
                        message
                );
    }

    @Test
    void shouldDelegateEvenWhenProcessorReturnsFailedFuture() {

        TdApi.Message message =
                createTextMessage(
                        false,
                        new TdApi.MessageSenderUser(USER_ID),
                        "Hello"
                );

        CompletableFuture<PlatformMessageRequest> failedFuture =
                CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "processing failed"
                        )
                );

        when(messageProcessor.process(
                CHANNEL_ACCOUNT_ID,
                message
        )).thenReturn(failedFuture);

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(messageProcessor)
                .process(
                        CHANNEL_ACCOUNT_ID,
                        message
                );
    }

    private TdApi.Message createTextMessage(
            boolean outgoing,
            TdApi.MessageSender sender,
            String text
    ) {
        TdApi.Message message =
                new TdApi.Message();

        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = outgoing;
        message.senderId = sender;
        message.date = 1_700_000_000;

        message.content =
                new TdApi.MessageText(
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