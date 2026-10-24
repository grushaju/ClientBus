package kit.penny.clientbus.server.connector.telegram;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.command.SendMessageCommand;
import kit.penny.clientbus.server.connector.telegram.account.TelegramAccountProfileSynchronizer;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientManager;
import kit.penny.clientbus.server.connector.telegram.client.TelegramInboundMessageProcessor;
import kit.penny.clientbus.server.service.ChannelAttachment;
import kit.penny.clientbus.server.connector.SyncConversationHistoryResult;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.exception.TdlibException;
import kit.penny.tdlib.query.TdlibResponse;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramChannelConnectorTest {

    private static final UUID CHANNEL_ACCOUNT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final long CHAT_ID = 123456789L;

    @Mock
    private TelegramClientManager telegramClientManager;

    @Mock
    private TelegramClientContext telegramClientContext;

    @Mock
    private TelegramClient telegramClient;

    @Mock
    private TelegramConversationMapper telegramConversationMapper;

    @Mock
    private TelegramInboundMessageProcessor telegramInboundMessageProcessor;

    @Mock
    private TelegramAccountProfileSynchronizer profileSynchronizer;

    @Mock
    private IAttachmentStorage attachmentStorage;

    private TelegramChannelConnector connector;

    @BeforeEach
    void setUp() {
        connector = new TelegramChannelConnector(
                telegramClientManager,
                telegramConversationMapper,
                profileSynchronizer,
                attachmentStorage
        );

    }

    @Test
    void shouldSupportTelegramOnly() {
        assertTrue(connector.supports(ChannelType.TELEGRAM));

        assertFalse(connector.supports(ChannelType.WHATSAPP));
        assertFalse(connector.supports(ChannelType.MAX));
        assertFalse(connector.supports(ChannelType.VK));
    }

    @Test
    void shouldSendText() {
        stubTelegramClient();
        TdApi.Message telegramMessage = telegramMessage(987654321L);

        when(telegramClient.send(any()))
                .thenReturn(new TdlibResponse<>(
                        telegramMessage,
                        null
                ));

        SendMessageCommand request = new SendMessageCommand(
                UUID.randomUUID(),
                CHANNEL_ACCOUNT_ID,
                String.valueOf(CHAT_ID),
                MessageType.TEXT,
                "Hello Telegram",
                List.of()
        );

        ConnectorSendResult result =
                connector.handle(request);

        assertEquals(
                "987654321",
                result.externalId()
        );

        verify(telegramClientManager)
                .require(CHANNEL_ACCOUNT_ID);

        verify(telegramClientContext)
                .telegramClient();

        ArgumentCaptor<TdApi.Function<?>> captor =
                ArgumentCaptor.forClass(TdApi.Function.class);

        verify(telegramClient).send(captor.capture());

        TdApi.Function<?> function = captor.getValue();

        assertInstanceOf(
                TdApi.SendMessage.class,
                function
        );

        TdApi.SendMessage sendMessage =
                (TdApi.SendMessage) function;

        assertEquals(
                CHAT_ID,
                sendMessage.chatId
        );

        assertInstanceOf(
                TdApi.InputMessageText.class,
                sendMessage.inputMessageContent
        );

        TdApi.InputMessageText content =
                (TdApi.InputMessageText)
                        sendMessage.inputMessageContent;

        assertEquals(
                "Hello Telegram",
                content.text.text
        );
    }

    @Test
    void shouldSendImage() throws Exception {
        stubTelegramClient();
        TdApi.Message telegramMessage =
                telegramMessage(1001L);

        Path temporaryFileDuringSend =
                sendAndCaptureTemporaryFile(
                        telegramMessage,
                        MessageType.IMAGE,
                        MessageAttachmentType.IMAGE,
                        "photo.jpg",
                        "image/jpeg",
                        "image-content"
                );

        assertNotNull(temporaryFileDuringSend);

        assertTrue(
                Files.exists(temporaryFileDuringSend),
                "Temporary Telegram file must remain until Telegram send completion"
        );
    }

    @Test
    void shouldSendAudio() throws Exception {
        stubTelegramClient();
        TdApi.Message telegramMessage =
                telegramMessage(1002L);

        Path temporaryFileDuringSend =
                sendAndCaptureTemporaryFile(
                        telegramMessage,
                        MessageType.AUDIO,
                        MessageAttachmentType.AUDIO,
                        "audio.mp3",
                        "audio/mpeg",
                        "audio-content"
                );

        assertNotNull(temporaryFileDuringSend);

        assertTrue(
                Files.exists(temporaryFileDuringSend),
                "Temporary Telegram file must remain until Telegram send completion"
        );
    }

    @Test
    void shouldUseCorrectTelegramClientForChannelAccount() {
        stubTelegramClient();
        TdApi.Message telegramMessage =
                telegramMessage(1003L);

        when(telegramClient.send(any()))
                .thenReturn(new TdlibResponse<>(
                        telegramMessage,
                        null
                ));

        SendMessageCommand request = new SendMessageCommand(
                UUID.randomUUID(),
                CHANNEL_ACCOUNT_ID,
                String.valueOf(CHAT_ID),
                MessageType.TEXT,
                "Hello",
                List.of()
        );

        connector.handle(request);

        verify(telegramClientManager)
                .require(CHANNEL_ACCOUNT_ID);

        verify(telegramClientContext)
                .telegramClient();

        verify(telegramClient)
                .send(any());

        verifyNoMoreInteractions(telegramClientManager);
    }

    @Test
    void shouldReturnTelegramMessageIdAsExternalId() {
        stubTelegramClient();
        TdApi.Message telegramMessage =
                telegramMessage(555777999L);

        when(telegramClient.send(any()))
                .thenReturn(new TdlibResponse<>(
                        telegramMessage,
                        null
                ));

        SendMessageCommand request = textRequest(
                "Hello"
        );

        ConnectorSendResult result =
                connector.handle(request);

        assertEquals(
                "555777999",
                result.externalId()
        );
    }

    @Test
    void shouldDeleteTemporaryFileWhenTelegramSendFails()
            throws Exception {
        stubTelegramClient();
        Path[] temporaryFile = new Path[1];

        when(telegramClient.send(any()))
                .thenAnswer(invocation -> {

                    TdApi.SendMessage sendMessage =
                            (TdApi.SendMessage)
                                    invocation.getArgument(0);

                    temporaryFile[0] =
                            extractTemporaryFile(
                                    sendMessage.inputMessageContent
                            );

                    throw new IllegalStateException(
                            "TDLib send failed"
                    );
                });

        ChannelAttachment attachment =
                attachment(
                        MessageAttachmentType.IMAGE,
                        "photo.jpg",
                        "image/jpeg",
                        "image-content"
                );

        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.IMAGE,
                        "caption",
                        List.of(attachment)
                );

        assertThrows(
                IllegalStateException.class,
                () -> connector.handle(request)
        );

        assertNotNull(temporaryFile[0]);

        assertFalse(
                Files.exists(temporaryFile[0]),
                "Temporary Telegram file must be deleted after failed send"
        );
    }

    @Test
    void shouldRejectNullRequest() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> connector.handle((SendMessageCommand)null)
                );

        assertEquals(
                "SendMessageCommand must not be null",
                exception.getMessage()
        );

        verifyNoInteractions(telegramClientManager);
    }

    @Test
    void shouldRejectMissingChannelAccountId() {
        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        null,
                        String.valueOf(CHAT_ID),
                        MessageType.TEXT,
                        "Hello",
                        List.of()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.handle(request)
        );

        verifyNoInteractions(telegramClientManager);
    }

    @Test
    void shouldRejectBlankRecipientExternalId() {
        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        " ",
                        MessageType.TEXT,
                        "Hello",
                        List.of()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.handle(request)
        );

        verifyNoInteractions(telegramClientManager);
    }

    @Test
    void shouldRejectNonNumericTelegramChatId() {
        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        "not-a-chat-id",
                        MessageType.TEXT,
                        "Hello",
                        List.of()
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> connector.handle(request)
                );

        assertTrue(
                exception.getMessage()
                        .contains(
                                "recipientExternalId must be a numeric chat ID"
                        )
        );

        verifyNoInteractions(telegramClientManager);
    }

    @Test
    void shouldRejectNullMessageType() {
        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        null,
                        "Hello",
                        List.of()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.handle(request)
        );

        verifyNoInteractions(telegramClientManager);
    }

    @Test
    void shouldRejectBlankTextContent() {
        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.TEXT,
                        " ",
                        List.of()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.handle(request)
        );

        verifyNoInteractions(telegramClient);
    }

    @Test
    void shouldRejectTextWithAttachments() {
        ChannelAttachment attachment =
                attachment(
                        MessageAttachmentType.IMAGE,
                        "photo.jpg",
                        "image/jpeg",
                        "image"
                );

        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.TEXT,
                        "Hello",
                        List.of(attachment)
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.handle(request)
        );

        verifyNoInteractions(telegramClientManager);
    }

    @Test
    void shouldRejectImageWithoutAttachment() {
        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.IMAGE,
                        "caption",
                        List.of()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.handle(request)
        );

        verifyNoInteractions(telegramClient);
    }

    @Test
    void shouldRejectImageWithMultipleAttachments() {
        ChannelAttachment first =
                attachment(
                        MessageAttachmentType.IMAGE,
                        "first.jpg",
                        "image/jpeg",
                        "first"
                );

        ChannelAttachment second =
                attachment(
                        MessageAttachmentType.IMAGE,
                        "second.jpg",
                        "image/jpeg",
                        "second"
                );

        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.IMAGE,
                        "caption",
                        List.of(first, second)
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.handle(request)
        );

        verifyNoInteractions(telegramClient);
    }

    @Test
    void shouldRejectWrongAttachmentType() {
        ChannelAttachment attachment =
                attachment(
                        MessageAttachmentType.AUDIO,
                        "audio.mp3",
                        "audio/mpeg",
                        "audio"
                );

        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.IMAGE,
                        "caption",
                        List.of(attachment)
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> connector.handle(request)
                );

        assertTrue(
                exception.getMessage()
                        .contains("Expected Telegram attachment type IMAGE")
        );

        verifyNoInteractions(telegramClient);
    }

    @Test
    void shouldRejectNullAttachmentContent() {
        ChannelAttachment attachment =
                new ChannelAttachment(
                        MessageAttachmentType.IMAGE,
                        "photo.jpg",
                        "image/jpeg",
                        10,
                        null
                );
        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.IMAGE,
                        "caption",
                        List.of(attachment)
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> connector.handle(request)
                );

        assertEquals(
                "Telegram attachment content must not be null",
                exception.getMessage()
        );

        verifyNoInteractions(telegramClientManager);
        verifyNoInteractions(telegramClientContext);
        verifyNoInteractions(telegramClient);
    }



    @Test
    void shouldRejectUnsupportedMessageType() {
        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.VIDEO,
                        null,
                        List.of()
                );

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> connector.handle(request)
                );

        assertTrue(
                exception.getMessage()
                        .contains(
                                "Unsupported Telegram outbound message type"
                        )
        );

        verifyNoInteractions(telegramClient);
    }

    @Test
    void shouldPropagateTelegramClientManagerFailure() {
        when(telegramClientManager.require(CHANNEL_ACCOUNT_ID))
                .thenThrow(
                        new IllegalStateException(
                                "Telegram account is not running"
                        )
                );

        SendMessageCommand request =
                textRequest("Hello");

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> connector.handle(request)
                );

        assertEquals(
                "Telegram account is not running",
                exception.getMessage()
        );

        verify(telegramClientManager)
                .require(CHANNEL_ACCOUNT_ID);

        verifyNoInteractions(telegramClient);
    }

    @Test
    void shouldPropagateTelegramErrorResponse() {
        stubTelegramClient();
        TdApi.Error error = mock(TdApi.Error.class);

        when(telegramClient.send(any()))
                .thenReturn(
                        new TdlibResponse<>(
                                null,
                                error
                        )
                );

        SendMessageCommand request =
                textRequest("Hello");

        assertThrows(
                RuntimeException.class,
                () -> connector.handle(request)
        );

        verify(telegramClient).send(any());
    }

    @Test
    void shouldWrapAttachmentReadIOException() {
        stubTelegramClient();
        InputStream failingStream =
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException(
                                "simulated read failure"
                        );
                    }
                };

        ChannelAttachment attachment =
                new ChannelAttachment(
                        MessageAttachmentType.IMAGE,
                        "photo.jpg",
                        "image/jpeg",
                        100,
                        failingStream
                );

        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.IMAGE,
                        "caption",
                        List.of(attachment)
                );

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> connector.handle(request)
                );

        assertEquals(
                "Failed to prepare Telegram attachment",
                exception.getMessage()
        );

        assertInstanceOf(
                IOException.class,
                exception.getCause()
        );

        verifyNoInteractions(telegramClient);
    }

    private SendMessageCommand textRequest(
            String text
    ) {
        return new SendMessageCommand(
                UUID.randomUUID(),
                CHANNEL_ACCOUNT_ID,
                String.valueOf(CHAT_ID),
                MessageType.TEXT,
                text,
                List.of()
        );
    }

    private ChannelAttachment attachment(
            MessageAttachmentType type,
            String fileName,
            String contentType,
            String content
    ) {
        byte[] bytes =
                content.getBytes(StandardCharsets.UTF_8);

        return new ChannelAttachment(
                type,
                fileName,
                contentType,
                bytes.length,
                new ByteArrayInputStream(bytes)
        );
    }

    private TdApi.Message telegramMessage(
            long messageId
    ) {
        TdApi.Message message =
                new TdApi.Message();

        message.id = messageId;

        return message;
    }

    private Path sendAndCaptureTemporaryFile(
            TdApi.Message telegramMessage,
            MessageType messageType,
            MessageAttachmentType attachmentType,
            String fileName,
            String contentType,
            String content
    ) throws Exception {

        Path[] temporaryFile =
                new Path[1];

        when(telegramClient.send(any()))
                .thenAnswer(invocation -> {

                    TdApi.SendMessage sendMessage =
                            (TdApi.SendMessage)
                                    invocation.getArgument(0);

                    temporaryFile[0] =
                            extractTemporaryFile(
                                    sendMessage.inputMessageContent
                            );

                    assertNotNull(
                            temporaryFile[0]
                    );

                    assertTrue(
                            Files.exists(temporaryFile[0]),
                            "Temporary file must exist while TDLib send is running"
                    );

                    assertEquals(
                            content,
                            Files.readString(
                                    temporaryFile[0]
                            )
                    );

                    return new TdlibResponse<>(
                            telegramMessage,
                            null
                    );
                });

        ChannelAttachment attachment =
                attachment(
                        attachmentType,
                        fileName,
                        contentType,
                        content
                );

        SendMessageCommand request =
                new SendMessageCommand(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        messageType,
                        "caption",
                        List.of(attachment)
                );

        ConnectorSendResult result =
                connector.handle(request);

        assertEquals(
                String.valueOf(telegramMessage.id),
                result.externalId()
        );

        verify(telegramClient)
                .send(any());

        return temporaryFile[0];
    }

    @Test
    void shouldResolvePrivateChatBeforeRequestingUncachedHistory() {
        stubTelegramClient();
        boolean[] chatKnown = {false};
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = CHAT_ID;
        chat.type = new TdApi.ChatTypePrivate(CHAT_ID);

        when(telegramClient.send(any())).thenAnswer(invocation -> {
            TdApi.Function<?> query = invocation.getArgument(0);
            if (query instanceof TdApi.CreatePrivateChat create) {
                assertEquals(CHAT_ID, create.userId);
                assertFalse(create.force);
                chatKnown[0] = true;
                return new TdlibResponse<>(chat, null);
            }
            if (query instanceof TdApi.GetChatHistory history) {
                if (!chatKnown[0]) {
                    return new TdlibResponse<>(null, new TdApi.Error(400, "Chat not found"));
                }
                assertEquals(chat.id, history.chatId);
                assertEquals(0, history.fromMessageId);
                assertEquals(50, history.limit);
                assertFalse(history.onlyLocal);
                return new TdlibResponse<>(new TdApi.Messages(0, new TdApi.Message[0]), null);
            }
            throw new AssertionError("Unexpected TDLib request: " + query.getClass().getSimpleName());
        });

        var result = connector.handle(new SyncConversationHistoryCommand(
                CHANNEL_ACCOUNT_ID, String.valueOf(CHAT_ID), null, 50));

        assertTrue(result.historyStartReached());
        assertTrue(result.messages().isEmpty());
        var order = inOrder(telegramClient);
        order.verify(telegramClient).send(any(TdApi.CreatePrivateChat.class));
        order.verify(telegramClient, times(2)).send(any(TdApi.GetChatHistory.class));
        order.verifyNoMoreInteractions();
    }

    @Test
    void shouldNotRequestHistoryWhenPrivateChatResolutionFails() {
        stubTelegramClient();
        var error = new TdApi.Error(400, "User not found");
        when(telegramClient.send(any())).thenAnswer(invocation -> {
            TdApi.Function<?> query = invocation.getArgument(0);
            if (query instanceof TdApi.CreatePrivateChat) return new TdlibResponse<>(null, error);
            throw new AssertionError("History must not be queried before resolving its chat");
        });

        var failure = assertThrows(TdlibException.class, () -> connector.handle(new SyncConversationHistoryCommand(
                CHANNEL_ACCOUNT_ID, String.valueOf(CHAT_ID), "100", 50)));

        assertSame(error, failure.getError());
        verify(telegramClient, never()).send(any(TdApi.GetChatHistory.class));
        verifyNoInteractions(telegramInboundMessageProcessor);
    }

    @Test
    void shouldFailTheWholeHistoryPageWhenOneMessageCannotBeConverted() {
        stubHistoryClient();
        var newest = telegramMessage(99L);
        var broken = telegramMessage(98L);
        var oldest = telegramMessage(97L);
        when(telegramClient.send(any(TdApi.GetChatHistory.class)))
                .thenReturn(new TdlibResponse<>(new TdApi.Messages(3, new TdApi.Message[]{newest, broken, oldest}), null));
        var applicationContext = mock(ConfigurableApplicationContext.class);
        when(telegramClientContext.applicationContext()).thenReturn(applicationContext);
        when(applicationContext.getBean(TelegramInboundMessageProcessor.class)).thenReturn(telegramInboundMessageProcessor);
        var mapped = new PlatformMessageRequest(CHANNEL_ACCOUNT_ID, String.valueOf(CHAT_ID), null, null, null,
                String.valueOf(CHAT_ID), "99", MessageType.TEXT, "Cached first message", null, Instant.EPOCH, List.of());
        var cause = new IllegalStateException("Attachment download failed");
        when(telegramInboundMessageProcessor.process(CHANNEL_ACCOUNT_ID, newest)).thenReturn(CompletableFuture.completedFuture(mapped));
        when(telegramInboundMessageProcessor.process(CHANNEL_ACCOUNT_ID, broken)).thenReturn(CompletableFuture.failedFuture(cause));

        var failure = assertThrows(CompletionException.class, () -> connector.handle(new SyncConversationHistoryCommand(
                CHANNEL_ACCOUNT_ID, String.valueOf(CHAT_ID), "100", 50)));

        assertSame(cause, failure.getCause());
        verify(telegramInboundMessageProcessor, never()).process(CHANNEL_ACCOUNT_ID, oldest);
    }

    @Test
    void shouldMarkHistoryStartReachedWhenTelegramReturnsEmptyHistory() {
        stubHistoryClient();

        when(telegramClient.send(any(TdApi.GetChatHistory.class)))
                .thenReturn(new TdlibResponse<>(
                        new TdApi.Messages(
                                0,
                                new TdApi.Message[0]
                        ),
                        null
                ));

        SyncConversationHistoryCommand command =
                new SyncConversationHistoryCommand(
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        null,
                        50
                );

        SyncConversationHistoryResult result =
                connector.handle(command);

        assertTrue(result.historyStartReached());
        assertTrue(result.messages().isEmpty());
    }

    @Test
    void shouldRejectARepeatedCursorOnlyResponseWithoutMarkingTheHistoryEnd() {
        stubHistoryClient();

        long cursorMessageId = 100L;

        when(telegramClient.send(any(TdApi.GetChatHistory.class)))
                .thenReturn(new TdlibResponse<>(
                        new TdApi.Messages(
                                1,
                                new TdApi.Message[]{
                                        telegramMessage(cursorMessageId)
                                }
                        ),
                        null
                ));

        SyncConversationHistoryCommand command =
                new SyncConversationHistoryCommand(
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        String.valueOf(cursorMessageId),
                        50
                );

        assertThrows(IllegalStateException.class, () -> connector.handle(command));
        verify(telegramClient, times(2)).send(any(TdApi.GetChatHistory.class));
        verifyNoInteractions(telegramInboundMessageProcessor);
    }

    @Test void limitOneStillImportsAnOlderMessageWithTdlibsExclusiveCursor() {
        stubHistoryClient();
        var older = telegramMessage(99L);
        when(telegramClient.send(any(TdApi.GetChatHistory.class)))
                .thenAnswer(invocation -> {
                    TdApi.GetChatHistory query = invocation.getArgument(0);
                    assertEquals(100L, query.fromMessageId); assertEquals(0, query.offset); assertEquals(1, query.limit);
                    return new TdlibResponse<>(new TdApi.Messages(1, new TdApi.Message[]{older}), null);
                });
        stubHistoryProcessor();
        var mapped = historyMessage("99");
        when(telegramInboundMessageProcessor.process(CHANNEL_ACCOUNT_ID, older)).thenReturn(CompletableFuture.completedFuture(mapped));
        var result = connector.handle(new SyncConversationHistoryCommand(CHANNEL_ACCOUNT_ID, String.valueOf(CHAT_ID), "100", 1));
        assertEquals(List.of(mapped), result.messages()); assertFalse(result.historyStartReached());
        verify(telegramClient).send(any(TdApi.GetChatHistory.class));
    }

    @Test void aCursorOnlyPageCanRecoverOnTheSingleBoundedRetry() {
        stubHistoryClient(); stubHistoryProcessor();
        var older = telegramMessage(99L);
        when(telegramClient.send(any(TdApi.GetChatHistory.class)))
                .thenReturn(new TdlibResponse<>(new TdApi.Messages(1, new TdApi.Message[]{telegramMessage(100L)}), null))
                .thenReturn(new TdlibResponse<>(new TdApi.Messages(1, new TdApi.Message[]{older}), null));
        when(telegramInboundMessageProcessor.process(CHANNEL_ACCOUNT_ID, older)).thenReturn(CompletableFuture.completedFuture(historyMessage("99")));
        var result = connector.handle(new SyncConversationHistoryCommand(CHANNEL_ACCOUNT_ID, String.valueOf(CHAT_ID), "100", 50));
        assertEquals(1, result.messages().size()); assertFalse(result.historyStartReached());
        verify(telegramClient, times(2)).send(any(TdApi.GetChatHistory.class));
    }

    @Test void anEmptyInitialRetryCannotDiscardAnAlreadyReceivedPage() {
        stubHistoryClient(); stubHistoryProcessor();
        var first = telegramMessage(100L);
        when(telegramClient.send(any(TdApi.GetChatHistory.class)))
                .thenReturn(new TdlibResponse<>(new TdApi.Messages(1, new TdApi.Message[]{first}), null))
                .thenReturn(new TdlibResponse<>(new TdApi.Messages(0, new TdApi.Message[0]), null));
        when(telegramInboundMessageProcessor.process(CHANNEL_ACCOUNT_ID, first)).thenReturn(CompletableFuture.completedFuture(historyMessage("100")));
        var result = connector.handle(new SyncConversationHistoryCommand(CHANNEL_ACCOUNT_ID, String.valueOf(CHAT_ID), null, 50));
        assertEquals(1, result.messages().size()); assertFalse(result.historyStartReached());
    }

    @Test void invalidHistoryResponseIsAnErrorRatherThanTheHistoryEnd() {
        stubHistoryClient();
        when(telegramClient.send(any(TdApi.GetChatHistory.class)))
                .thenReturn(new TdlibResponse<>(new TdApi.Messages(0, null), null));
        assertThrows(IllegalStateException.class, () -> connector.handle(new SyncConversationHistoryCommand(
                CHANNEL_ACCOUNT_ID, String.valueOf(CHAT_ID), "100", 50)));
    }

    @Test void historyImportsOnlyMessagesOlderThanTheRequestedCursor() {
        stubHistoryClient(); stubHistoryProcessor();
        var newer = telegramMessage(101L); var cursor = telegramMessage(100L); var older = telegramMessage(99L);
        when(telegramClient.send(any(TdApi.GetChatHistory.class)))
                .thenReturn(new TdlibResponse<>(new TdApi.Messages(3, new TdApi.Message[]{newer, cursor, older}), null));
        when(telegramInboundMessageProcessor.process(CHANNEL_ACCOUNT_ID, older)).thenReturn(CompletableFuture.completedFuture(historyMessage("99")));
        var result = connector.handle(new SyncConversationHistoryCommand(CHANNEL_ACCOUNT_ID, String.valueOf(CHAT_ID), "100", 50));
        assertEquals(1, result.messages().size()); assertFalse(result.historyStartReached());
        verify(telegramInboundMessageProcessor, never()).process(CHANNEL_ACCOUNT_ID, newer);
        verify(telegramInboundMessageProcessor, never()).process(CHANNEL_ACCOUNT_ID, cursor);
    }

    private void stubHistoryProcessor() {
        var application = mock(ConfigurableApplicationContext.class);
        when(telegramClientContext.applicationContext()).thenReturn(application);
        when(application.getBean(TelegramInboundMessageProcessor.class)).thenReturn(telegramInboundMessageProcessor);
    }

    private PlatformMessageRequest historyMessage(String id) {
        return new PlatformMessageRequest(CHANNEL_ACCOUNT_ID, String.valueOf(CHAT_ID), null, null, null,
                String.valueOf(CHAT_ID), id, MessageType.TEXT, "History", null, Instant.EPOCH, List.of());
    }

    @Test
    void shouldNotMarkHistoryStartReachedWhenResponseContainsOlderMessage() {
        stubHistoryClient();

        long cursorMessageId = 100L;

        TdApi.Message olderMessage =
                telegramMessage(99L);

        when(telegramClient.send(any(TdApi.GetChatHistory.class)))
                .thenReturn(new TdlibResponse<>(
                        new TdApi.Messages(
                                2,
                                new TdApi.Message[]{
                                        telegramMessage(cursorMessageId),
                                        olderMessage
                                }
                        ),
                        null
                ));

        SyncConversationHistoryCommand command =
                new SyncConversationHistoryCommand(
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        String.valueOf(cursorMessageId),
                        50
                );

        ConfigurableApplicationContext applicationContext =
                mock(ConfigurableApplicationContext.class);

        when(telegramClientContext.applicationContext())
                .thenReturn(applicationContext);

        when(applicationContext.getBean(TelegramInboundMessageProcessor.class))
                .thenReturn(telegramInboundMessageProcessor);

        when(telegramInboundMessageProcessor.process(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        SyncConversationHistoryResult result =
                connector.handle(command);

        assertFalse(
                result.historyStartReached(),
                "An older message proves that history continues"
        );
    }

    @Test
    void shouldNotMarkHistoryStartReachedForShortPageWhenHistoryContinues() {
        stubHistoryClient();

        when(telegramClient.send(any(TdApi.GetChatHistory.class)))
                .thenReturn(new TdlibResponse<>(
                        new TdApi.Messages(
                                2,
                                new TdApi.Message[]{
                                        telegramMessage(200L),
                                        telegramMessage(199L)
                                }
                        ),
                        null
                ));

        SyncConversationHistoryCommand command =
                new SyncConversationHistoryCommand(
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        null,
                        50
                );

        ConfigurableApplicationContext applicationContext =
                mock(ConfigurableApplicationContext.class);

        when(telegramClientContext.applicationContext())
                .thenReturn(applicationContext);

        when(applicationContext.getBean(TelegramInboundMessageProcessor.class))
                .thenReturn(telegramInboundMessageProcessor);

        when(telegramInboundMessageProcessor.process(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        SyncConversationHistoryResult result =
                connector.handle(command);

        assertFalse(
                result.historyStartReached(),
                "A non-empty page without a cursor is not evidence of EOF"
        );
    }

    private Path extractTemporaryFile(
            TdApi.InputMessageContent content
    ) {
        if (content instanceof TdApi.InputMessagePhoto photo) {

            TdApi.InputPhoto inputPhoto =
                    photo.photo;

            TdApi.InputFile inputFile =
                    inputPhoto.photo;

            assertInstanceOf(
                    TdApi.InputFileLocal.class,
                    inputFile
            );

            return Path.of(
                    ((TdApi.InputFileLocal) inputFile).path
            );
        }

        if (content instanceof TdApi.InputMessageAudio audio) {

            TdApi.InputAudio inputAudio =
                    audio.audio;

            TdApi.InputFile inputFile =
                    inputAudio.audio;

            assertInstanceOf(
                    TdApi.InputFileLocal.class,
                    inputFile
            );

            return Path.of(
                    ((TdApi.InputFileLocal) inputFile).path
            );
        }

        throw new AssertionError(
                "Unsupported Telegram content: "
                        + content.getClass().getName()
        );
    }

    private void stubHistoryClient() {
        stubTelegramClient();
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = CHAT_ID;
        chat.type = new TdApi.ChatTypePrivate(CHAT_ID);
        when(telegramClient.send(any(TdApi.CreatePrivateChat.class)))
                .thenReturn(new TdlibResponse<>(chat, null));
    }

    private void stubTelegramClient() {
        when(telegramClientManager.require(CHANNEL_ACCOUNT_ID))
                .thenReturn(telegramClientContext);

        when(telegramClientContext.telegramClient())
                .thenReturn(telegramClient);

    }
}
