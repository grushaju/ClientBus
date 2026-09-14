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
import kit.penny.tdlib.updates.ITdlibUpdateListener;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class TelegramInboundMessageListener
        implements ITdlibUpdateListener<TdApi.UpdateNewMessage> {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TelegramInboundMessageListener.class
            );

    private final UUID channelAccountId;

    private final ObjectProvider<TelegramClient>
            telegramClientProvider;

    private final TelegramUserService telegramUserService;

    private final IInboundEventPublisher
            inboundEventPublisher;

    private final IAttachmentStorage attachmentStorage;

    public TelegramInboundMessageListener(
            UUID channelAccountId,
            ObjectProvider<TelegramClient> telegramClientProvider,
            TelegramUserService telegramUserService,
            IInboundEventPublisher inboundEventPublisher,
            IAttachmentStorage attachmentStorage
    ) {
        this.channelAccountId = channelAccountId;
        this.telegramClientProvider = telegramClientProvider;
        this.telegramUserService = telegramUserService;
        this.inboundEventPublisher = inboundEventPublisher;
        this.attachmentStorage = attachmentStorage;
    }

    @Override
    public void handleNotification(
            TdApi.UpdateNewMessage notification
    ) {
        if (notification == null
                || notification.message == null) {
            return;
        }

        TdApi.Message message =
                notification.message;

        if (message.isOutgoing) {
            log.debug(
                    "Ignoring outgoing Telegram message: " +
                            "channelAccountId={}, messageId={}",
                    channelAccountId,
                    message.id
            );
            return;
        }

        if (!(message.senderId
                instanceof TdApi.MessageSenderUser sender)) {

            log.debug(
                    "Ignoring Telegram message with unsupported sender: " +
                            "channelAccountId={}, messageId={}, senderType={}",
                    channelAccountId,
                    message.id,
                    message.senderId == null
                            ? "null"
                            : message.senderId
                            .getClass()
                            .getSimpleName()
            );
            return;
        }

        telegramClientProvider
                .getObject()
                .sendAsync(
                        new TdApi.GetChat(message.chatId)
                )
                .thenAccept(response ->
                        handleChatResponse(
                                message,
                                sender,
                                response
                        )
                );
    }

    private void handleChatResponse(
            TdApi.Message message,
            TdApi.MessageSenderUser sender,
            TdlibResponse<TdApi.Chat> response
    ) {
        if (response.getError().isPresent()) {
            log.warn(
                    "Failed to load Telegram chat: " +
                            "channelAccountId={}, chatId={}, messageId={}, error={}",
                    channelAccountId,
                    message.chatId,
                    message.id,
                    response.getError().get().message
            );
            return;
        }

        TdApi.Chat chat =
                response.getObject().orElse(null);

        if (chat == null) {
            log.warn(
                    "Telegram chat response is empty: " +
                            "channelAccountId={}, chatId={}, messageId={}",
                    channelAccountId,
                    message.chatId,
                    message.id
            );
            return;
        }

        if (!(chat.type
                instanceof TdApi.ChatTypePrivate)) {

            log.debug(
                    "Ignoring Telegram message from non-private chat: " +
                            "channelAccountId={}, chatId={}, messageId={}, chatType={}",
                    channelAccountId,
                    message.chatId,
                    message.id,
                    chat.type == null
                            ? "null"
                            : chat.type
                            .getClass()
                            .getSimpleName()
            );
            return;
        }

        telegramUserService
                .getUser(sender.userId)
                .thenAccept(userResponse ->
                        handleUserResponse(
                                message,
                                sender,
                                userResponse
                        )
                );
    }

    private void handleUserResponse(
            TdApi.Message message,
            TdApi.MessageSenderUser sender,
            TdlibResponse<TdApi.User> response
    ) {
        if (response.getError().isPresent()) {
            log.warn(
                    "Failed to load Telegram user: " +
                            "channelAccountId={}, userId={}, messageId={}, error={}",
                    channelAccountId,
                    sender.userId,
                    message.id,
                    response.getError().get().message
            );
            return;
        }

        TdApi.User user =
                response.getObject().orElse(null);

        if (user == null) {
            log.warn(
                    "Telegram user response is empty: " +
                            "channelAccountId={}, userId={}, messageId={}",
                    channelAccountId,
                    sender.userId,
                    message.id
            );
            return;
        }

        CompletableFuture<InboundContent> contentFuture =
                resolveContent(message);

        contentFuture.thenAccept(content ->
                publishMessage(
                        message,
                        user,
                        content
                )
        ).exceptionally(error -> {
            log.error(
                    "Failed to process Telegram attachment: " +
                            "channelAccountId={}, messageId={}",
                    channelAccountId,
                    message.id,
                    error
            );
            return null;
        });
    }

    private CompletableFuture<InboundContent> resolveContent(
            TdApi.Message message
    ) {
        if (message.content
                instanceof TdApi.MessageText messageText) {

            String content =
                    messageText.text == null
                            ? null
                            : messageText.text.text;

            return CompletableFuture.completedFuture(
                    new InboundContent(
                            MessageType.TEXT,
                            content,
                            List.of()
                    )
            );
        }

        if (message.content
                instanceof TdApi.MessagePhoto messagePhoto) {

            return downloadPhoto(
                    message.id,
                    messagePhoto
            ).thenApply(attachment ->
                    new InboundContent(
                            MessageType.IMAGE,
                            extractCaption(messagePhoto.caption),
                            List.of(attachment)
                    )
            );
        }

        if (message.content
                instanceof TdApi.MessageAudio messageAudio) {

            return downloadAudio(
                    message.id,
                    messageAudio
            ).thenApply(attachment ->
                    new InboundContent(
                            MessageType.AUDIO,
                            extractCaption(messageAudio.caption),
                            List.of(attachment)
                    )
            );
        }

        log.debug(
                "Ignoring unsupported Telegram message content: " +
                        "channelAccountId={}, messageId={}, contentType={}",
                channelAccountId,
                message.id,
                message.content == null
                        ? "null"
                        : message.content
                        .getClass()
                        .getSimpleName()
        );

        return CompletableFuture.completedFuture(null);
    }

    private void publishMessage(
            TdApi.Message message,
            TdApi.User user,
            InboundContent content
    ) {
        if (content == null) {
            return;
        }

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccountId,
                        Long.toString(user.id),
                        extractUsername(user),
                        user.phoneNumber,
                        buildDisplayName(
                                user.firstName,
                                user.lastName
                        ),
                        Long.toString(message.id),
                        content.messageType(),
                        content.text(),
                        null,
                        Instant.ofEpochSecond(
                                message.date
                        )
                );

        try {
            PlatformInboundMessageEvent event =
                    new PlatformInboundMessageEvent(
                            request,
                            content.attachments()
                    );

            inboundEventPublisher.publish(event);

            log.debug(
                    "Telegram inbound message processed: " +
                            "channelAccountId={}, chatId={}, messageId={}, " +
                            "clientExternalId={}, attachmentCount={}",
                    channelAccountId,
                    message.chatId,
                    message.id,
                    user.id,
                    content.attachments().size()
            );

        } catch (RuntimeException e) {
            cleanupAttachments(
                    content.attachments()
            );

            log.error(
                    "Failed to process Telegram inbound message: " +
                            "channelAccountId={}, chatId={}, messageId={}",
                    channelAccountId,
                    message.chatId,
                    message.id,
                    e
            );
        }
    }

    private CompletableFuture<PlatformInboundAttachment>
    downloadPhoto(
            long messageId,
            TdApi.MessagePhoto messagePhoto
    ) {
        TdApi.PhotoSize photoSize =
                selectLargestPhotoSize(
                        messagePhoto.photo
                );

        return downloadFile(
                photoSize.photo
        ).thenApply(file ->
                storeDownloadedFile(
                        file,
                        MessageAttachmentType.IMAGE,
                        "telegram-" + messageId + ".jpg",
                        "image/jpeg"
                )
        );
    }

    private CompletableFuture<PlatformInboundAttachment>
    downloadAudio(
            long messageId,
            TdApi.MessageAudio messageAudio
    ) {
        TdApi.Audio audio =
                messageAudio.audio;

        String fileName =
                audio.fileName == null
                        || audio.fileName.isBlank()
                        ? "telegram-" + messageId
                        : audio.fileName;

        String contentType =
                audio.mimeType == null
                        || audio.mimeType.isBlank()
                        ? "application/octet-stream"
                        : audio.mimeType;

        return downloadFile(
                audio.audio
        ).thenApply(file ->
                storeDownloadedFile(
                        file,
                        MessageAttachmentType.AUDIO,
                        fileName,
                        contentType
                )
        );
    }

    private TdApi.PhotoSize selectLargestPhotoSize(
            TdApi.Photo photo
    ) {
        if (photo == null
                || photo.sizes == null
                || photo.sizes.length == 0) {

            throw new IllegalStateException(
                    "Telegram photo has no sizes"
            );
        }

        return Arrays.stream(photo.sizes)
                .max(
                        Comparator.comparingLong(
                                size ->
                                        (long) size.width
                                                * size.height
                        )
                )
                .orElseThrow();
    }

    private CompletableFuture<TdApi.File> downloadFile(
            TdApi.File file
    ) {
        if (file == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "Telegram attachment has no file"
                    )
            );
        }

        return telegramClientProvider
                .getObject()
                .sendAsync(
                        new TdApi.DownloadFile(
                                file.id,
                                32,
                                0,
                                0,
                                true
                        )
                )
                .thenCompose(response -> {

                    if (response.getError().isPresent()) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "Failed to download Telegram file: "
                                                + response
                                                .getError()
                                                .get()
                                                .message
                                )
                        );
                    }

                    TdApi.File downloaded =
                            response.getObject().orElse(null);

                    if (downloaded == null
                            || downloaded.local == null
                            || downloaded.local.path == null
                            || downloaded.local.path.isBlank()) {

                        return CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "Telegram file was downloaded " +
                                                "without local path"
                                )
                        );
                    }

                    return CompletableFuture.completedFuture(
                            downloaded
                    );
                });
    }

    private PlatformInboundAttachment
    storeDownloadedFile(
            TdApi.File file,
            MessageAttachmentType type,
            String fileName,
            String contentType
    ) {
        Path path =
                Path.of(file.local.path);

        try {
            long size =
                    Files.size(path);

            StoredAttachmentMetadata stored;

            try (InputStream inputStream =
                         Files.newInputStream(path)) {

                stored =
                        attachmentStorage.store(
                                inputStream,
                                fileName,
                                size,
                                contentType
                        );
            }

            return new PlatformInboundAttachment(
                    type,
                    stored.storageKey(),
                    stored.fileName(),
                    stored.contentType(),
                    stored.size()
            );

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read downloaded Telegram file: "
                            + path,
                    e
            );
        }
    }

    private void cleanupAttachments(
            List<PlatformInboundAttachment> attachments
    ) {
        if (attachments == null) {
            return;
        }

        for (PlatformInboundAttachment attachment :
                attachments) {

            if (attachment == null) {
                continue;
            }

            try {
                attachmentStorage.delete(
                        attachment.storageKey()
                );
            } catch (RuntimeException cleanupError) {
                log.warn(
                        "Failed to cleanup Telegram attachment: " +
                                "channelAccountId={}, storageKey={}",
                        channelAccountId,
                        attachment.storageKey(),
                        cleanupError
                );
            }
        }
    }

    private String extractCaption(
            TdApi.FormattedText caption
    ) {
        if (caption == null) {
            return null;
        }

        return caption.text;
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

    @Override
    public Class<TdApi.UpdateNewMessage> notificationType() {
        return TdApi.UpdateNewMessage.class;
    }

    private record InboundContent(
            MessageType messageType,
            String text,
            List<PlatformInboundAttachment> attachments
    ) {
    }
}