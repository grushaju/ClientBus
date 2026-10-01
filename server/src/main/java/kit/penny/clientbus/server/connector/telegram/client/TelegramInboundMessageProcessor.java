package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.common.dto.message.PlatformMessageAttachment;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.storage.IAttachmentStorage;
import kit.penny.clientbus.server.storage.StoredAttachmentMetadata;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.service.TelegramUserService;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

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

public class TelegramInboundMessageProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TelegramInboundMessageProcessor.class
            );

    private final ObjectProvider<TelegramClient>
            telegramClientProvider;

    private final TelegramUserService telegramUserService;

    private final IAttachmentStorage attachmentStorage;

    public TelegramInboundMessageProcessor(
            ObjectProvider<TelegramClient> telegramClientProvider,
            TelegramUserService telegramUserService,
            IAttachmentStorage attachmentStorage
    ) {
        this.telegramClientProvider =
                telegramClientProvider;

        this.telegramUserService =
                telegramUserService;

        this.attachmentStorage =
                attachmentStorage;
    }

    public CompletableFuture<PlatformMessageRequest> process(
            UUID channelAccountId,
            TdApi.Message message
    ) {
        if (channelAccountId == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "channelAccountId must not be null"
                    )
            );
        }

        if (message == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Telegram message must not be null"
                    )
            );
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

            return CompletableFuture.completedFuture(null);
        }

        return telegramClientProvider
                .getObject()
                .sendAsync(
                        new TdApi.GetChat(message.chatId)
                )
                .thenCompose(response -> {

                    if (response.getError().isPresent()) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "Failed to load Telegram chat: "
                                                + response
                                                .getError()
                                                .get()
                                                .message
                                )
                        );
                    }

                    TdApi.Chat chat =
                            response.getObject().orElse(null);

                    if (chat == null) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException(
                                        "Telegram chat response is empty"
                                )
                        );
                    }

                    if (!(chat.type
                            instanceof TdApi.ChatTypePrivate privateChat)) {

                        log.debug(
                                "Ignoring Telegram message from non-private chat: " +
                                        "channelAccountId={}, chatId={}, " +
                                        "messageId={}, chatType={}",
                                channelAccountId,
                                message.chatId,
                                message.id,
                                chat.type == null
                                        ? "null"
                                        : chat.type
                                        .getClass()
                                        .getSimpleName()
                        );

                        return CompletableFuture.completedFuture(
                                null
                        );
                    }

                    return telegramUserService
                            .getUser(privateChat.userId)
                            .thenCompose(userResponse -> {

                                if (userResponse.getError().isPresent()) {
                                    return CompletableFuture.failedFuture(
                                            new IllegalStateException(
                                                    "Failed to load Telegram user: "
                                                            + userResponse
                                                            .getError()
                                                            .get()
                                                            .message
                                            )
                                    );
                                }

                                TdApi.User user =
                                        userResponse
                                                .getObject()
                                                .orElse(null);

                                if (user == null) {
                                    return CompletableFuture.failedFuture(
                                            new IllegalStateException(
                                                    "Telegram user response is empty"
                                            )
                                    );
                                }

                                return resolveContent(
                                        channelAccountId,
                                        message
                                ).thenApply(content -> {

                                    if (content == null) {
                                        return null;
                                    }

                                    return createPlatformMessage(
                                            channelAccountId,
                                            message,
                                            sender,
                                            user,
                                            content
                                    );
                                });
                            });
                });
    }

    private CompletableFuture<InboundContent> resolveContent(
            UUID channelAccountId,
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
                            extractCaption(
                                    messagePhoto.caption
                            ),
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
                            extractCaption(
                                    messageAudio.caption
                            ),
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

    private PlatformMessageRequest createPlatformMessage(
            UUID channelAccountId,
            TdApi.Message message,
            TdApi.MessageSenderUser sender,
            TdApi.User user,
            InboundContent content
    ) {
        return new PlatformMessageRequest(
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

    private PlatformMessageAttachment storeDownloadedFile(
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

    public void cleanupAttachments(
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
                                "storageKey={}",
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

    private record InboundContent(
            MessageType messageType,
            String text,
            List<PlatformMessageAttachment> attachments
    ) {
    }
}