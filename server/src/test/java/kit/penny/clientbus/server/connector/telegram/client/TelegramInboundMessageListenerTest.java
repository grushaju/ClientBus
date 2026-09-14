package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.dto.message.PlatformInboundAttachment;
import kit.penny.clientbus.common.dto.message.PlatformInboundMessageEvent;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.kafka.producer.IInboundEventPublisher;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.clientbus.server.storage.StoredAttachmentMetadata;
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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
    private IInboundEventPublisher inboundEventPublisher;

    @Mock
    private IAttachmentStorage attachmentStorage;

    @Mock
    private TelegramUserService telegramUserService;

    private TelegramInboundMessageListener listener;

    @BeforeEach
    void setUp() {
        listener = new TelegramInboundMessageListener(
                CHANNEL_ACCOUNT_ID,
                telegramClientProvider,
                telegramUserService,
                inboundEventPublisher,
                attachmentStorage
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

        ArgumentCaptor<PlatformInboundMessageEvent> eventCaptor =
                ArgumentCaptor.forClass(PlatformInboundMessageEvent.class);

        verify(inboundEventPublisher)
                .publish(eventCaptor.capture());

        PlatformInboundMessageEvent event =
                eventCaptor.getValue();

        InboundMessageRequest request =
                event.message();

        assertEquals(CHANNEL_ACCOUNT_ID, request.channelAccountId());
        assertEquals(Long.toString(USER_ID), request.clientExternalId());
        assertEquals(Long.toString(MESSAGE_ID), request.externalId());
        assertEquals(MessageType.TEXT, request.type());
        assertEquals("Hello", request.content());
        assertEquals("+79991234567", request.clientPhone());
        assertEquals("Ivan Ivanov", request.clientDisplayName());
        assertEquals(
                Instant.ofEpochSecond(1_700_000_000),
                request.sentAt()
        );

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

        verify(inboundEventPublisher, never())
                .publish(any());

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

        verify(inboundEventPublisher, never())
                .publish(any());

        verify(telegramClient, never())
                .sendAsync(any());
    }

    @Test
    void shouldIgnoreUnsupportedMessageContent() {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = new TdApi.Message();
        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = false;
        message.senderId =
                new TdApi.MessageSenderUser(USER_ID);

        message.content =
                new TdApi.MessageSticker();

        TdApi.Chat chat = createPrivateChat(CHAT_ID);

        mockGetChatSuccess(chat);

        TdApi.User user = new TdApi.User();
        user.id = USER_ID;

        TdlibResponse<TdApi.User> userResponse =
                new TdlibResponse<>(user, null);

        when(telegramUserService.getUser(USER_ID))
                .thenReturn(
                        CompletableFuture.completedFuture(userResponse)
                );

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(inboundEventPublisher, never())
                .publish(any());
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

        verify(inboundEventPublisher, never())
                .publish(any());
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

        verify(inboundEventPublisher, never())
                .publish(any());
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

        verify(inboundEventPublisher, never())
                .publish(any());
    }

    @Test
    void shouldReturnUpdateNewMessageAsNotificationType() {

        assertEquals(
                TdApi.UpdateNewMessage.class,
                listener.notificationType()
        );
    }

    @Test
    void shouldKeepInboundMessagesIsolatedBetweenAccounts() {

        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();

        long chatIdA = 101L;
        long chatIdB = 102L;

        long userIdA = 201L;
        long userIdB = 202L;

        long messageIdA = 301L;
        long messageIdB = 302L;

        TelegramClient clientA =
                Mockito.mock(TelegramClient.class);

        TelegramClient clientB =
                Mockito.mock(TelegramClient.class);

        ObjectProvider<TelegramClient> providerA =
                Mockito.mock(ObjectProvider.class);

        ObjectProvider<TelegramClient> providerB =
                Mockito.mock(ObjectProvider.class);

        TelegramUserService userService =
                Mockito.mock(TelegramUserService.class);

        when(providerA.getObject())
                .thenReturn(clientA);

        when(providerB.getObject())
                .thenReturn(clientB);

        TdApi.Chat chatA = createPrivateChat(chatIdA);
        TdApi.Chat chatB = createPrivateChat(chatIdB);

        TdApi.User userA = new TdApi.User();
        userA.id = userIdA;
        userA.firstName = "User";
        userA.lastName = "A";
        userA.phoneNumber = "+79990000001";

        TdApi.User userB = new TdApi.User();
        userB.id = userIdB;
        userB.firstName = "User";
        userB.lastName = "B";
        userB.phoneNumber = "+79990000002";

        when(userService.getUser(userIdA))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new TdlibResponse<>(userA, null)
                        )
                );

        when(userService.getUser(userIdB))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new TdlibResponse<>(userB, null)
                        )
                );

        TdApi.Message messageA = createTextMessage(
                false,
                new TdApi.MessageSenderUser(userIdA),
                "Hello from A"
        );

        messageA.id = messageIdA;
        messageA.chatId = chatIdA;

        TdApi.Message messageB = createTextMessage(
                false,
                new TdApi.MessageSenderUser(userIdB),
                "Hello from B"
        );

        messageB.id = messageIdB;
        messageB.chatId = chatIdB;

        doReturn(
                CompletableFuture.completedFuture(
                        new TdlibResponse<>(chatA, null)
                )
        ).when(clientA).sendAsync(any());

        doReturn(
                CompletableFuture.completedFuture(
                        new TdlibResponse<>(chatB, null)
                )
        ).when(clientB).sendAsync(any());

        TelegramInboundMessageListener listenerA =
                new TelegramInboundMessageListener(
                        accountA,
                        providerA,
                        userService,
                        inboundEventPublisher,
                        attachmentStorage
                );

        TelegramInboundMessageListener listenerB =
                new TelegramInboundMessageListener(
                        accountB,
                        providerB,
                        userService,
                        inboundEventPublisher,
                        attachmentStorage
                );

        listenerA.handleNotification(
                new TdApi.UpdateNewMessage(messageA)
        );

        listenerB.handleNotification(
                new TdApi.UpdateNewMessage(messageB)
        );

        ArgumentCaptor<PlatformInboundMessageEvent> eventCaptor =
                ArgumentCaptor.forClass(
                        PlatformInboundMessageEvent.class
                );

        verify(inboundEventPublisher, Mockito.times(2))
                .publish(eventCaptor.capture());

        List<PlatformInboundMessageEvent> events =
                eventCaptor.getAllValues();

        assertEquals(2, events.size());

        InboundMessageRequest requestA =
                events.stream()
                        .map(PlatformInboundMessageEvent::message)
                        .filter(request ->
                                request.channelAccountId()
                                        .equals(accountA))
                        .findFirst()
                        .orElseThrow();

        InboundMessageRequest requestB =
                events.stream()
                        .map(PlatformInboundMessageEvent::message)
                        .filter(request ->
                                request.channelAccountId()
                                        .equals(accountB))
                        .findFirst()
                        .orElseThrow();

        assertEquals(
                accountA,
                requestA.channelAccountId()
        );

        assertEquals(
                Long.toString(userIdA),
                requestA.clientExternalId()
        );

        assertEquals(
                Long.toString(messageIdA),
                requestA.externalId()
        );

        assertEquals(
                "Hello from A",
                requestA.content()
        );

        assertEquals(
                accountB,
                requestB.channelAccountId()
        );

        assertEquals(
                Long.toString(userIdB),
                requestB.clientExternalId()
        );

        assertEquals(
                Long.toString(messageIdB),
                requestB.externalId()
        );

        assertEquals(
                "Hello from B",
                requestB.content()
        );

        verify(providerA).getObject();
        verify(providerB).getObject();

        verify(clientA).sendAsync(any());
        verify(clientB).sendAsync(any());

        verify(userService).getUser(userIdA);
        verify(userService).getUser(userIdB);
    }

    @Test
    void shouldProcessInboundPhotoMessage() throws Exception {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = new TdApi.Message();
        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = false;
        message.senderId =
                new TdApi.MessageSenderUser(USER_ID);

        TdApi.PhotoSize small = new TdApi.PhotoSize();
        small.width = 100;
        small.height = 100;
        small.photo = new TdApi.File();
        small.photo.id = 101;

        TdApi.PhotoSize large = new TdApi.PhotoSize();
        large.width = 1000;
        large.height = 800;
        large.photo = new TdApi.File();
        large.photo.id = 102;

        TdApi.Photo photo = new TdApi.Photo();
        photo.sizes = new TdApi.PhotoSize[]{
                small,
                large
        };

        TdApi.FormattedText caption =
                new TdApi.FormattedText();
        caption.text = "Photo caption";

        TdApi.MessagePhoto messagePhoto =
                new TdApi.MessagePhoto();

        messagePhoto.photo = photo;
        messagePhoto.caption = caption;

        message.content = messagePhoto;

        TdApi.Chat chat = createPrivateChat(CHAT_ID);

        TdApi.User user = new TdApi.User();
        user.id = USER_ID;

        TdlibResponse<TdApi.User> userResponse =
                new TdlibResponse<>(user, null);

        when(telegramUserService.getUser(USER_ID))
                .thenReturn(
                        CompletableFuture.completedFuture(userResponse)
                );

        Path tempFile = Files.createTempFile(
                "telegram-photo-",
                ".jpg"
        );

        try {
            Files.write(
                    tempFile,
                    new byte[]{1, 2, 3, 4, 5}
            );

            TdApi.File downloadedFile = new TdApi.File();
            downloadedFile.id = 102;
            downloadedFile.local = new TdApi.LocalFile();
            downloadedFile.local.path = tempFile.toString();

            /*
             * Одним mock обрабатываем оба TDLib вызова:
             *
             * 1. GetChat
             * 2. DownloadFile
             */
            when(telegramClient.sendAsync(any()))
                    .thenAnswer(invocation -> {

                        Object function =
                                invocation.getArgument(0);

                        if (function instanceof TdApi.GetChat) {

                            return CompletableFuture.completedFuture(
                                    new TdlibResponse<>(chat, null)
                            );
                        }

                        if (function instanceof TdApi.DownloadFile) {

                            TdApi.DownloadFile downloadFile =
                                    (TdApi.DownloadFile) function;

                            assertThat(downloadFile.fileId)
                                    .isEqualTo(102);

                            return CompletableFuture.completedFuture(
                                    new TdlibResponse<>(
                                            downloadedFile,
                                            null
                                    )
                            );
                        }

                        throw new IllegalStateException(
                                "Unexpected TDLib function: "
                                        + function.getClass().getName()
                        );
                    });

            StoredAttachmentMetadata metadata =
                    new StoredAttachmentMetadata(
                            "telegram/storage/photo-102",
                            "telegram-" + MESSAGE_ID + ".jpg",
                            "image/jpeg",
                            5
                    );

            when(attachmentStorage.store(
                    any(InputStream.class),
                    eq("telegram-" + MESSAGE_ID + ".jpg"),
                    eq(5L),
                    eq("image/jpeg")
            )).thenReturn(metadata);

            listener.handleNotification(
                    new TdApi.UpdateNewMessage(message)
            );

            ArgumentCaptor<PlatformInboundMessageEvent> captor =
                    ArgumentCaptor.forClass(
                            PlatformInboundMessageEvent.class
                    );

            verify(inboundEventPublisher)
                    .publish(captor.capture());

            PlatformInboundMessageEvent event =
                    captor.getValue();

            assertThat(event)
                    .isNotNull();

            assertThat(event.attachments().size())
                    .isEqualTo(1);

            PlatformInboundAttachment attachment =
                    event.attachments().get(0);

            assertThat(attachment.type())
                    .isEqualTo(MessageAttachmentType.IMAGE);

            assertThat(attachment.storageKey())
                    .isEqualTo(
                            "telegram/storage/photo-102"
                    );

            assertThat(attachment.fileName())
                    .isEqualTo(
                            "telegram-" + MESSAGE_ID + ".jpg"
                    );

            assertThat(attachment.contentType())
                    .isEqualTo("image/jpeg");

            assertThat(attachment.size())
                    .isEqualTo(5);

            verify(attachmentStorage)
                    .store(
                            any(InputStream.class),
                            eq("telegram-" + MESSAGE_ID + ".jpg"),
                            eq(5L),
                            eq("image/jpeg")
                    );

            verify(telegramClient)
                    .sendAsync(any(TdApi.GetChat.class));

            verify(telegramClient)
                    .sendAsync(any(TdApi.DownloadFile.class));
        }
        finally {
            Files.deleteIfExists(tempFile);
        }
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

    @Test
    void shouldProcessInboundAudioMessage() throws Exception {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = new TdApi.Message();
        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = false;
        message.senderId =
                new TdApi.MessageSenderUser(USER_ID);

        TdApi.Audio audio = new TdApi.Audio();
        audio.audio = new TdApi.File();
        audio.audio.id = 201;
        audio.fileName = "voice-message.mp3";
        audio.mimeType = "audio/mpeg";

        TdApi.FormattedText caption =
                new TdApi.FormattedText();

        caption.text = "Audio caption";

        TdApi.MessageAudio messageAudio =
                new TdApi.MessageAudio();

        messageAudio.audio = audio;
        messageAudio.caption = caption;

        message.content = messageAudio;

        TdApi.Chat chat = createPrivateChat(CHAT_ID);

        mockGetChatSuccess(chat);

        TdApi.User user = new TdApi.User();
        user.id = USER_ID;

        TdlibResponse<TdApi.User> userResponse =
                new TdlibResponse<>(user, null);

        when(telegramUserService.getUser(USER_ID))
                .thenReturn(
                        CompletableFuture.completedFuture(userResponse)
                );

        Path tempFile = Files.createTempFile(
                "telegram-audio-",
                ".mp3"
        );

        Files.write(
                tempFile,
                new byte[]{10, 20, 30, 40, 50, 60}
        );

        TdApi.File downloadedFile = new TdApi.File();
        downloadedFile.id = 201;
        downloadedFile.local = new TdApi.LocalFile();
        downloadedFile.local.path = tempFile.toString();

        when(telegramClient.sendAsync(
                any(TdApi.DownloadFile.class)
        )).thenReturn(
                CompletableFuture.completedFuture(
                        new TdlibResponse<>(downloadedFile, null)
                )
        );

        StoredAttachmentMetadata metadata =
                new StoredAttachmentMetadata(
                        "telegram/storage/audio-201",
                        "voice-message.mp3",
                        "audio/mpeg",
                        6
                );

        when(attachmentStorage.store(
                any(InputStream.class),
                eq("voice-message.mp3"),
                eq(6L),
                eq("audio/mpeg")
        )).thenReturn(metadata);

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        ArgumentCaptor<PlatformInboundMessageEvent> captor =
                ArgumentCaptor.forClass(
                        PlatformInboundMessageEvent.class
                );

        verify(inboundEventPublisher)
                .publish(captor.capture());

        PlatformInboundMessageEvent event =
                captor.getValue();

        assertThat(event.attachments().size())
                .isEqualTo(1);

        PlatformInboundAttachment attachment =
                event.attachments().get(0);

        assertThat(attachment.type())
                .isEqualTo(MessageAttachmentType.AUDIO);

        assertThat(attachment.storageKey())
                .isEqualTo("telegram/storage/audio-201");

        assertThat(attachment.fileName())
                .isEqualTo("voice-message.mp3");

        assertThat(attachment.contentType())
                .isEqualTo("audio/mpeg");

        assertThat(attachment.size())
                .isEqualTo(6);

        assertThat(event.message().content())
                .isEqualTo("Audio caption");

        assertThat(event.message().type())
                .isEqualTo(MessageType.AUDIO);

        ArgumentCaptor<TdApi.Function<?>> functionCaptor =
                ArgumentCaptor.forClass(TdApi.Function.class);

        verify(telegramClient, atLeastOnce())
                .sendAsync(functionCaptor.capture());

        assertThat(
                functionCaptor.getAllValues()
                        .stream()
                        .filter(function -> function instanceof TdApi.DownloadFile)
                        .map(function -> (TdApi.DownloadFile) function)
                        .anyMatch(function -> function.fileId == 201)
        )
                .isTrue();

        verify(attachmentStorage)
                .store(
                        any(InputStream.class),
                        eq("voice-message.mp3"),
                        eq(6L),
                        eq("audio/mpeg")
                );

        Files.deleteIfExists(tempFile);
    }

    @Test
    void shouldNotPublishWhenPhotoDownloadFails() {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = new TdApi.Message();
        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = false;
        message.senderId =
                new TdApi.MessageSenderUser(USER_ID);

        TdApi.PhotoSize photoSize = new TdApi.PhotoSize();
        photoSize.width = 1000;
        photoSize.height = 800;
        photoSize.photo = new TdApi.File();
        photoSize.photo.id = 301;

        TdApi.Photo photo = new TdApi.Photo();
        photo.sizes = new TdApi.PhotoSize[]{
                photoSize
        };

        TdApi.MessagePhoto messagePhoto =
                new TdApi.MessagePhoto();

        messagePhoto.photo = photo;
        messagePhoto.caption =
                new TdApi.FormattedText();

        message.content = messagePhoto;

        TdApi.Chat chat = createPrivateChat(CHAT_ID);

        TdApi.User user = new TdApi.User();
        user.id = USER_ID;

        when(telegramUserService.getUser(USER_ID))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new TdlibResponse<>(user, null)
                        )
                );

        when(telegramClient.sendAsync(any()))
                .thenAnswer(invocation -> {

                    Object function =
                            invocation.getArgument(0);

                    if (function instanceof TdApi.GetChat) {
                        return CompletableFuture.completedFuture(
                                new TdlibResponse<>(chat, null)
                        );
                    }

                    if (function instanceof TdApi.DownloadFile) {
                        return CompletableFuture.completedFuture(
                                new TdlibResponse<>(
                                        null,
                                        new TdApi.Error(
                                                500,
                                                "Download failed"
                                        )
                                )
                        );
                    }

                    throw new IllegalStateException(
                            "Unexpected TDLib function: "
                                    + function.getClass().getName()
                    );
                });

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(inboundEventPublisher, never())
                .publish(any());

        verify(attachmentStorage, never())
                .store(
                        any(InputStream.class),
                        anyString(),
                        anyLong(),
                        anyString()
                );
    }

    @Test
    void shouldNotPublishWhenAudioDownloadFails() {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = new TdApi.Message();
        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = false;
        message.senderId =
                new TdApi.MessageSenderUser(USER_ID);

        TdApi.Audio audio = new TdApi.Audio();
        audio.audio = new TdApi.File();
        audio.audio.id = 401;
        audio.fileName = "failed-audio.mp3";
        audio.mimeType = "audio/mpeg";

        TdApi.MessageAudio messageAudio =
                new TdApi.MessageAudio();

        messageAudio.audio = audio;
        messageAudio.caption =
                new TdApi.FormattedText();

        message.content = messageAudio;

        TdApi.Chat chat = createPrivateChat(CHAT_ID);

        TdApi.User user = new TdApi.User();
        user.id = USER_ID;

        when(telegramUserService.getUser(USER_ID))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new TdlibResponse<>(user, null)
                        )
                );

        when(telegramClient.sendAsync(any()))
                .thenAnswer(invocation -> {

                    Object function =
                            invocation.getArgument(0);

                    if (function instanceof TdApi.GetChat) {
                        return CompletableFuture.completedFuture(
                                new TdlibResponse<>(chat, null)
                        );
                    }

                    if (function instanceof TdApi.DownloadFile) {
                        return CompletableFuture.completedFuture(
                                new TdlibResponse<>(
                                        null,
                                        new TdApi.Error(
                                                500,
                                                "Download failed"
                                        )
                                )
                        );
                    }

                    throw new IllegalStateException(
                            "Unexpected TDLib function: "
                                    + function.getClass().getName()
                    );
                });

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(inboundEventPublisher, never())
                .publish(any());

        verify(attachmentStorage, never())
                .store(
                        any(InputStream.class),
                        anyString(),
                        anyLong(),
                        anyString()
                );
    }

    @Test
    void shouldNotPublishWhenDownloadedPhotoHasNoLocalPath() {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = new TdApi.Message();
        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = false;
        message.senderId =
                new TdApi.MessageSenderUser(USER_ID);

        TdApi.PhotoSize photoSize = new TdApi.PhotoSize();
        photoSize.width = 800;
        photoSize.height = 600;
        photoSize.photo = new TdApi.File();
        photoSize.photo.id = 501;

        TdApi.Photo photo = new TdApi.Photo();
        photo.sizes = new TdApi.PhotoSize[]{
                photoSize
        };

        TdApi.MessagePhoto messagePhoto =
                new TdApi.MessagePhoto();

        messagePhoto.photo = photo;
        messagePhoto.caption =
                new TdApi.FormattedText();

        message.content = messagePhoto;

        TdApi.Chat chat = createPrivateChat(CHAT_ID);

        TdApi.User user = new TdApi.User();
        user.id = USER_ID;

        when(telegramUserService.getUser(USER_ID))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new TdlibResponse<>(user, null)
                        )
                );

        TdApi.File downloadedFile = new TdApi.File();
        downloadedFile.id = 501;
        downloadedFile.local = new TdApi.LocalFile();

        // local.path intentionally remains null

        when(telegramClient.sendAsync(any()))
                .thenAnswer(invocation -> {

                    Object function =
                            invocation.getArgument(0);

                    if (function instanceof TdApi.GetChat) {
                        return CompletableFuture.completedFuture(
                                new TdlibResponse<>(chat, null)
                        );
                    }

                    if (function instanceof TdApi.DownloadFile) {
                        return CompletableFuture.completedFuture(
                                new TdlibResponse<>(
                                        downloadedFile,
                                        null
                                )
                        );
                    }

                    throw new IllegalStateException(
                            "Unexpected TDLib function: "
                                    + function.getClass().getName()
                    );
                });

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(inboundEventPublisher, never())
                .publish(any());

        verify(attachmentStorage, never())
                .store(
                        any(InputStream.class),
                        anyString(),
                        anyLong(),
                        anyString()
                );
    }

    @Test
    void shouldNotPublishWhenDownloadedAudioHasNoLocalPath() {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = new TdApi.Message();
        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = false;
        message.senderId =
                new TdApi.MessageSenderUser(USER_ID);

        TdApi.Audio audio = new TdApi.Audio();
        audio.audio = new TdApi.File();
        audio.audio.id = 601;
        audio.fileName = "broken-audio.mp3";
        audio.mimeType = "audio/mpeg";

        TdApi.MessageAudio messageAudio =
                new TdApi.MessageAudio();

        messageAudio.audio = audio;
        messageAudio.caption =
                new TdApi.FormattedText();

        message.content = messageAudio;

        TdApi.Chat chat = createPrivateChat(CHAT_ID);

        TdApi.User user = new TdApi.User();
        user.id = USER_ID;

        when(telegramUserService.getUser(USER_ID))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new TdlibResponse<>(user, null)
                        )
                );

        TdApi.File downloadedFile = new TdApi.File();
        downloadedFile.id = 601;
        downloadedFile.local = new TdApi.LocalFile();

        // local.path intentionally remains null

        when(telegramClient.sendAsync(any()))
                .thenAnswer(invocation -> {

                    Object function =
                            invocation.getArgument(0);

                    if (function instanceof TdApi.GetChat) {
                        return CompletableFuture.completedFuture(
                                new TdlibResponse<>(chat, null)
                        );
                    }

                    if (function instanceof TdApi.DownloadFile) {
                        return CompletableFuture.completedFuture(
                                new TdlibResponse<>(
                                        downloadedFile,
                                        null
                                )
                        );
                    }

                    throw new IllegalStateException(
                            "Unexpected TDLib function: "
                                    + function.getClass().getName()
                    );
                });

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(inboundEventPublisher, never())
                .publish(any());

        verify(attachmentStorage, never())
                .store(
                        any(InputStream.class),
                        anyString(),
                        anyLong(),
                        anyString()
                );
    }

    @Test
    void shouldNotPublishWhenPhotoSizesAreNull() {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = new TdApi.Message();
        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = false;
        message.senderId =
                new TdApi.MessageSenderUser(USER_ID);

        TdApi.Photo photo = new TdApi.Photo();

        // sizes intentionally null

        TdApi.MessagePhoto messagePhoto =
                new TdApi.MessagePhoto();

        messagePhoto.photo = photo;
        messagePhoto.caption =
                new TdApi.FormattedText();

        message.content = messagePhoto;

        TdApi.Chat chat = createPrivateChat(CHAT_ID);

        TdApi.User user = new TdApi.User();
        user.id = USER_ID;

        when(telegramUserService.getUser(USER_ID))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new TdlibResponse<>(user, null)
                        )
                );

        mockGetChatSuccess(chat);

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(inboundEventPublisher, never())
                .publish(any());

        verify(attachmentStorage, never())
                .store(
                        any(InputStream.class),
                        anyString(),
                        anyLong(),
                        anyString()
                );

        verify(telegramClient, never())
                .sendAsync(any(TdApi.DownloadFile.class));
    }

    @Test
    void shouldNotPublishWhenPhotoSizesAreEmpty() {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = new TdApi.Message();
        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = false;
        message.senderId =
                new TdApi.MessageSenderUser(USER_ID);

        TdApi.Photo photo = new TdApi.Photo();
        photo.sizes = new TdApi.PhotoSize[0];

        TdApi.MessagePhoto messagePhoto =
                new TdApi.MessagePhoto();

        messagePhoto.photo = photo;
        messagePhoto.caption =
                new TdApi.FormattedText();

        message.content = messagePhoto;

        TdApi.Chat chat = createPrivateChat(CHAT_ID);

        TdApi.User user = new TdApi.User();
        user.id = USER_ID;

        when(telegramUserService.getUser(USER_ID))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new TdlibResponse<>(user, null)
                        )
                );

        mockGetChatSuccess(chat);

        listener.handleNotification(
                new TdApi.UpdateNewMessage(message)
        );

        verify(inboundEventPublisher, never())
                .publish(any());

        verify(attachmentStorage, never())
                .store(
                        any(InputStream.class),
                        anyString(),
                        anyLong(),
                        anyString()
                );

        verify(telegramClient, never())
                .sendAsync(any(TdApi.DownloadFile.class));
    }

    @Test
    void shouldNotPublishWhenAttachmentStorageFails() throws Exception {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = new TdApi.Message();
        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = false;
        message.senderId =
                new TdApi.MessageSenderUser(USER_ID);

        TdApi.PhotoSize photoSize = new TdApi.PhotoSize();
        photoSize.width = 1000;
        photoSize.height = 800;
        photoSize.photo = new TdApi.File();
        photoSize.photo.id = 701;

        TdApi.Photo photo = new TdApi.Photo();
        photo.sizes = new TdApi.PhotoSize[]{
                photoSize
        };

        TdApi.MessagePhoto messagePhoto =
                new TdApi.MessagePhoto();

        messagePhoto.photo = photo;
        messagePhoto.caption =
                new TdApi.FormattedText();

        message.content = messagePhoto;

        TdApi.Chat chat = createPrivateChat(CHAT_ID);

        TdApi.User user = new TdApi.User();
        user.id = USER_ID;

        when(telegramUserService.getUser(USER_ID))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new TdlibResponse<>(user, null)
                        )
                );

        Path tempFile = Files.createTempFile(
                "telegram-storage-failure-",
                ".jpg"
        );

        try {
            Files.write(
                    tempFile,
                    new byte[]{1, 2, 3, 4, 5}
            );

            TdApi.File downloadedFile = new TdApi.File();
            downloadedFile.id = 701;
            downloadedFile.local = new TdApi.LocalFile();
            downloadedFile.local.path = tempFile.toString();

            when(telegramClient.sendAsync(any()))
                    .thenAnswer(invocation -> {

                        Object function =
                                invocation.getArgument(0);

                        if (function instanceof TdApi.GetChat) {
                            return CompletableFuture.completedFuture(
                                    new TdlibResponse<>(chat, null)
                            );
                        }

                        if (function instanceof TdApi.DownloadFile) {
                            return CompletableFuture.completedFuture(
                                    new TdlibResponse<>(
                                            downloadedFile,
                                            null
                                    )
                            );
                        }

                        throw new IllegalStateException(
                                "Unexpected TDLib function: "
                                        + function.getClass().getName()
                        );
                    });

            when(attachmentStorage.store(
                    any(InputStream.class),
                    eq("telegram-" + MESSAGE_ID + ".jpg"),
                    eq(5L),
                    eq("image/jpeg")
            )).thenThrow(
                    new RuntimeException("Storage unavailable")
            );

            listener.handleNotification(
                    new TdApi.UpdateNewMessage(message)
            );

            verify(inboundEventPublisher, never())
                    .publish(any());

            verify(attachmentStorage)
                    .store(
                            any(InputStream.class),
                            eq("telegram-" + MESSAGE_ID + ".jpg"),
                            eq(5L),
                            eq("image/jpeg")
                    );

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void shouldCleanupStoredAttachmentWhenPublishingFails()
            throws Exception {

        when(telegramClientProvider.getObject())
                .thenReturn(telegramClient);

        TdApi.Message message = new TdApi.Message();
        message.id = MESSAGE_ID;
        message.chatId = CHAT_ID;
        message.isOutgoing = false;
        message.senderId =
                new TdApi.MessageSenderUser(USER_ID);

        TdApi.PhotoSize photoSize = new TdApi.PhotoSize();
        photoSize.width = 1000;
        photoSize.height = 800;
        photoSize.photo = new TdApi.File();
        photoSize.photo.id = 801;

        TdApi.Photo photo = new TdApi.Photo();
        photo.sizes = new TdApi.PhotoSize[]{
                photoSize
        };

        TdApi.MessagePhoto messagePhoto =
                new TdApi.MessagePhoto();

        messagePhoto.photo = photo;

        TdApi.FormattedText caption =
                new TdApi.FormattedText();

        caption.text = "Cleanup test";

        messagePhoto.caption = caption;

        message.content = messagePhoto;

        TdApi.Chat chat = createPrivateChat(CHAT_ID);

        TdApi.User user = new TdApi.User();
        user.id = USER_ID;

        when(telegramUserService.getUser(USER_ID))
                .thenReturn(
                        CompletableFuture.completedFuture(
                                new TdlibResponse<>(user, null)
                        )
                );

        Path tempFile = Files.createTempFile(
                "telegram-cleanup-",
                ".jpg"
        );

        try {
            Files.write(
                    tempFile,
                    new byte[]{1, 2, 3, 4, 5}
            );

            TdApi.File downloadedFile = new TdApi.File();
            downloadedFile.id = 801;
            downloadedFile.local = new TdApi.LocalFile();
            downloadedFile.local.path = tempFile.toString();

            when(telegramClient.sendAsync(any()))
                    .thenAnswer(invocation -> {

                        Object function =
                                invocation.getArgument(0);

                        if (function instanceof TdApi.GetChat) {
                            return CompletableFuture.completedFuture(
                                    new TdlibResponse<>(chat, null)
                            );
                        }

                        if (function instanceof TdApi.DownloadFile) {
                            return CompletableFuture.completedFuture(
                                    new TdlibResponse<>(
                                            downloadedFile,
                                            null
                                    )
                            );
                        }

                        throw new IllegalStateException(
                                "Unexpected TDLib function: "
                                        + function.getClass().getName()
                        );
                    });

            StoredAttachmentMetadata metadata =
                    new StoredAttachmentMetadata(
                            "telegram/storage/cleanup-801",
                            "telegram-" + MESSAGE_ID + ".jpg",
                            "image/jpeg",
                            5
                    );

            when(attachmentStorage.store(
                    any(InputStream.class),
                    eq("telegram-" + MESSAGE_ID + ".jpg"),
                    eq(5L),
                    eq("image/jpeg")
            )).thenReturn(metadata);

            doThrow(
                    new RuntimeException("Publisher failed")
            ).when(inboundEventPublisher)
                    .publish(any());

            listener.handleNotification(
                    new TdApi.UpdateNewMessage(message)
            );

            verify(inboundEventPublisher)
                    .publish(any());

            verify(attachmentStorage)
                    .delete(
                            "telegram/storage/cleanup-801"
                    );

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }



    private TdApi.Chat createPrivateChat(long chatId) {

        TdApi.Chat chat = new TdApi.Chat();

        chat.id = chatId;
        chat.type =
                new TdApi.ChatTypePrivate(USER_ID);

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
