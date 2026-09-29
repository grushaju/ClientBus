package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.common.dto.message.PlatformMessageAttachment;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.kafka.producer.IInboundEventPublisher;
import kit.penny.clientbus.server.kafka.producer.IPlatformMessagePublisher;
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

    private final IPlatformMessagePublisher
            platformMessagePublisher;

    private final IAttachmentStorage attachmentStorage;

    public TelegramInboundMessageListener(
            UUID channelAccountId,
            ObjectProvider<TelegramClient> telegramClientProvider,
            TelegramUserService telegramUserService,
            IPlatformMessagePublisher platformMessagePublisher,
            IAttachmentStorage attachmentStorage
    ) {
        this.channelAccountId = channelAccountId;
        this.telegramClientProvider = telegramClientProvider;
        this.telegramUserService = telegramUserService;
        this.platformMessagePublisher = platformMessagePublisher;
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

        /*
         * Outgoing message with a sending state belongs to the
         * current ClientBus -> Telegram send flow.
         *
         * It will be correlated by TelegramMessageSendListener
         * using UpdateMessageSendSucceeded / UpdateMessageSendFailed.
         *
         * Outgoing message without a sending state was created
         * outside the current ClientBus send flow, for example
         * from another Telegram device.
         */
        if (message.isOutgoing
                && message.sendingState != null) {

            log.debug(
                    "Ignoring ClientBus-originated outgoing Telegram message: " +
                            "channelAccountId={}, messageId={}, sendingState={}",
                    channelAccountId,
                    message.id,
                    message.sendingState
                            .getClass()
                            .getSimpleName()
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
                instanceof TdApi.ChatTypePrivate privateChat)) {

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
                .getUser(privateChat.userId)
                .thenAccept(userResponse ->
                        handleUserResponse(
                                message,
                                sender,
                                privateChat.userId,
                                userResponse
                        )
                );
    }

    private void handleUserResponse(
            TdApi.Message message,
            TdApi.MessageSenderUser sender,
            long clientUserId,
            TdlibResponse<TdApi.User> response
    ) {
        if (response.getError().isPresent()) {
            log.warn(
                    "Failed to load Telegram user: " +
                            "channelAccountId={}, userId={}, messageId={}, error={}",
                    channelAccountId,
                    clientUserId,
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
                    clientUserId,
                    message.id
            );
            return;
        }

        CompletableFuture<InboundContent> contentFuture =
                resolveContent(message);

        contentFuture.thenAccept(content ->
                publishMessage(
                        message,
                        sender,
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
            TdApi.MessageSenderUser sender,
            TdApi.User user,
            InboundContent content
    ) {
        if (content == null) {
            return;
        }

        PlatformMessageRequest request =
                new PlatformMessageRequest(
                        channelAccountId,

                        /*
                         * In a private chat the client is the user
                         * represented by the chat itself.
                         */
                        Long.toString(user.id),

                        extractUsername(user),

                        user.phoneNumber,

                        buildDisplayName(
                                user.firstName,
                                user.lastName
                        ),

                        /*
                         * For inbound:
                         *     sender = client
                         *
                         * For external-device outbound:
                         *     sender = our Telegram account
                         *
                         * MessageProcessingService compares this value
                         * with ChannelAccount.externalId to determine
                         * the message direction.
                         */
                        Long.toString(sender.userId),

                        Long.toString(message.id),

                        content.messageType(),

                        content.text(),

                        null,

                        Instant.ofEpochSecond(
                                message.date
                        ),

                        content.attachments()
                );

        try {
            platformMessagePublisher.publish(
                    request
            );

            log.debug(
                    "Telegram platform message published: " +
                            "channelAccountId={}, chatId={}, messageId={}, " +
                            "senderExternalId={}, clientExternalId={}, " +
                            "outgoing={}, attachmentCount={}",
                    channelAccountId,
                    message.chatId,
                    message.id,
                    sender.userId,
                    user.id,
                    message.isOutgoing,
                    content.attachments().size()
            );

        } catch (RuntimeException e) {
            cleanupAttachments(
                    content.attachments()
            );

            log.error(
                    "Failed to publish Telegram platform message: " +
                            "channelAccountId={}, chatId={}, messageId={}",
                    channelAccountId,
                    message.chatId,
                    message.id,
                    e
            );
        }
    }

    private CompletableFuture<PlatformMessageAttachment>
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

    private CompletableFuture<PlatformMessageAttachment>
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

    private PlatformMessageAttachment
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

            return new PlatformMessageAttachment(
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
            List<PlatformMessageAttachment> attachments
    ) {
        if (attachments == null) {
            return;
        }

        for (PlatformMessageAttachment attachment :
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
            List<PlatformMessageAttachment> attachments
    ) {
    }
}