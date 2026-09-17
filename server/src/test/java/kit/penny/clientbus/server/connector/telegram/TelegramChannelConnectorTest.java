package kit.penny.clientbus.server.connector.telegram;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientManager;
import kit.penny.clientbus.server.service.ChannelAttachment;
import kit.penny.clientbus.server.service.ChannelSendRequest;
import kit.penny.clientbus.server.service.MessageService;
import kit.penny.tdlib.client.TelegramClient;
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

    private TelegramChannelConnector connector;

    @BeforeEach
    void setUp() {
        connector = new TelegramChannelConnector(
                telegramClientManager
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

        ChannelSendRequest request = new ChannelSendRequest(
                UUID.randomUUID(),
                CHANNEL_ACCOUNT_ID,
                String.valueOf(CHAT_ID),
                MessageType.TEXT,
                "Hello Telegram",
                List.of()
        );

        ConnectorSendResult result =
                connector.send(request);

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

        ChannelSendRequest request = new ChannelSendRequest(
                UUID.randomUUID(),
                CHANNEL_ACCOUNT_ID,
                String.valueOf(CHAT_ID),
                MessageType.TEXT,
                "Hello",
                List.of()
        );

        connector.send(request);

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

        ChannelSendRequest request = textRequest(
                "Hello"
        );

        ConnectorSendResult result =
                connector.send(request);

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

        ChannelSendRequest request =
                new ChannelSendRequest(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.IMAGE,
                        "caption",
                        List.of(attachment)
                );

        assertThrows(
                IllegalStateException.class,
                () -> connector.send(request)
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
                        () -> connector.send(null)
                );

        assertEquals(
                "ChannelSendRequest must not be null",
                exception.getMessage()
        );

        verifyNoInteractions(telegramClientManager);
    }

    @Test
    void shouldRejectMissingChannelAccountId() {
        ChannelSendRequest request =
                new ChannelSendRequest(
                        UUID.randomUUID(),
                        null,
                        String.valueOf(CHAT_ID),
                        MessageType.TEXT,
                        "Hello",
                        List.of()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.send(request)
        );

        verifyNoInteractions(telegramClientManager);
    }

    @Test
    void shouldRejectBlankRecipientExternalId() {
        ChannelSendRequest request =
                new ChannelSendRequest(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        " ",
                        MessageType.TEXT,
                        "Hello",
                        List.of()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.send(request)
        );

        verifyNoInteractions(telegramClientManager);
    }

    @Test
    void shouldRejectNonNumericTelegramChatId() {
        ChannelSendRequest request =
                new ChannelSendRequest(
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
                        () -> connector.send(request)
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
        ChannelSendRequest request =
                new ChannelSendRequest(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        null,
                        "Hello",
                        List.of()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.send(request)
        );

        verifyNoInteractions(telegramClientManager);
    }

    @Test
    void shouldRejectBlankTextContent() {
        ChannelSendRequest request =
                new ChannelSendRequest(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.TEXT,
                        " ",
                        List.of()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.send(request)
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

        ChannelSendRequest request =
                new ChannelSendRequest(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.TEXT,
                        "Hello",
                        List.of(attachment)
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.send(request)
        );

        verifyNoInteractions(telegramClientManager);
    }

    @Test
    void shouldRejectImageWithoutAttachment() {
        ChannelSendRequest request =
                new ChannelSendRequest(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.IMAGE,
                        "caption",
                        List.of()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.send(request)
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

        ChannelSendRequest request =
                new ChannelSendRequest(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        MessageType.IMAGE,
                        "caption",
                        List.of(first, second)
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connector.send(request)
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

        ChannelSendRequest request =
                new ChannelSendRequest(
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
                        () -> connector.send(request)
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
        ChannelSendRequest request =
                new ChannelSendRequest(
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
                        () -> connector.send(request)
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
        ChannelSendRequest request =
                new ChannelSendRequest(
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
                        () -> connector.send(request)
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

        ChannelSendRequest request =
                textRequest("Hello");

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> connector.send(request)
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

        ChannelSendRequest request =
                textRequest("Hello");

        assertThrows(
                RuntimeException.class,
                () -> connector.send(request)
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

        ChannelSendRequest request =
                new ChannelSendRequest(
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
                        () -> connector.send(request)
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

    private ChannelSendRequest textRequest(
            String text
    ) {
        return new ChannelSendRequest(
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

        ChannelSendRequest request =
                new ChannelSendRequest(
                        UUID.randomUUID(),
                        CHANNEL_ACCOUNT_ID,
                        String.valueOf(CHAT_ID),
                        messageType,
                        "caption",
                        List.of(attachment)
                );

        ConnectorSendResult result =
                connector.send(request);

        assertEquals(
                String.valueOf(telegramMessage.id),
                result.externalId()
        );

        verify(telegramClient)
                .send(any());

        return temporaryFile[0];
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

    private void stubTelegramClient() {
        when(telegramClientManager.require(CHANNEL_ACCOUNT_ID))
                .thenReturn(telegramClientContext);

        when(telegramClientContext.telegramClient())
                .thenReturn(telegramClient);

    }
}
