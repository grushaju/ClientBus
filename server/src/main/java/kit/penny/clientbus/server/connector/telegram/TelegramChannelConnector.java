package kit.penny.clientbus.server.connector.telegram;

import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientManager;
import kit.penny.clientbus.server.service.ChannelAttachment;
import kit.penny.clientbus.server.service.ChannelSendRequest;
import kit.penny.tdlib.client.TelegramClient;
import org.drinkless.tdlib.TdApi;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

@Component
public class TelegramChannelConnector implements IChannelConnector {

    private final TelegramClientManager telegramClientManager;

    public TelegramChannelConnector(
            TelegramClientManager telegramClientManager
    ) {
        this.telegramClientManager = telegramClientManager;
    }

    @Override
    public boolean supports(ChannelType channelType) {
        return channelType == ChannelType.TELEGRAM;
    }

    @Override
    public ConnectorSendResult send(ChannelSendRequest request) {
        validateRequest(request);

        long chatId =
                parseChatId(request.recipientExternalId());

        TelegramClientContext context =
                telegramClientManager.require(
                        request.channelAccountId()
                );

        TelegramClient telegramClient =
                context.telegramClient();

        return switch (request.type()) {
            case TEXT -> sendText(
                    telegramClient,
                    chatId,
                    request
            );

            case IMAGE -> sendImage(
                    telegramClient,
                    chatId,
                    request
            );

            case AUDIO -> sendAudio(
                    telegramClient,
                    chatId,
                    request
            );

            default -> throw new IllegalArgumentException(
                    "Unsupported Telegram outbound message type: "
                            + request.type()
            );
        };
    }

    private ConnectorSendResult sendText(
            TelegramClient telegramClient,
            long chatId,
            ChannelSendRequest request
    ) {
        TdApi.InputMessageText content =
                new TdApi.InputMessageText(
                        new TdApi.FormattedText(
                                request.content(),
                                new TdApi.TextEntity[0]
                        ),
                        null,
                        false
                );

        TdApi.Message message =
                telegramClient
                        .send(new TdApi.SendMessage(
                                chatId,
                                null,
                                null,
                                null,
                                null,
                                content
                        ))
                        .getObjectOrThrow();

        return new ConnectorSendResult(
                String.valueOf(message.id)
        );
    }

    private ConnectorSendResult sendImage(
            TelegramClient telegramClient,
            long chatId,
            ChannelSendRequest request
    ) {
        ChannelAttachment attachment =
                getSingleAttachment(
                        request,
                        MessageAttachmentType.IMAGE
                );

        return sendWithTemporaryFile(
                telegramClient,
                chatId,
                request,
                attachment,
                this::createImageContent
        );
    }

    private ConnectorSendResult sendAudio(
            TelegramClient telegramClient,
            long chatId,
            ChannelSendRequest request
    ) {
        ChannelAttachment attachment =
                getSingleAttachment(
                        request,
                        MessageAttachmentType.AUDIO
                );

        return sendWithTemporaryFile(
                telegramClient,
                chatId,
                request,
                attachment,
                this::createAudioContent
        );
    }

    private ConnectorSendResult sendWithTemporaryFile(
            TelegramClient telegramClient,
            long chatId,
            ChannelSendRequest request,
            ChannelAttachment attachment,
            TelegramContentFactory contentFactory
    ) {
        Path temporaryFile = null;

        try (InputStream inputStream = attachment.content()) {

            if (inputStream == null) {
                throw new IllegalArgumentException(
                        "Telegram attachment content must not be null"
                );
            }

            temporaryFile =
                    createTemporaryFile(
                            request.channelAccountId(),
                            request.messageId(),
                            attachment.fileName()
                    );

            Files.copy(
                    inputStream,
                    temporaryFile,
                    StandardCopyOption.REPLACE_EXISTING
            );

            TdApi.InputFileLocal inputFile =
                    new TdApi.InputFileLocal(
                            temporaryFile.toString()
                    );

            TdApi.InputMessageContent content =
                    contentFactory.create(
                            inputFile,
                            request
                    );

            TdApi.Message message =
                    telegramClient
                            .send(new TdApi.SendMessage(
                                    chatId,
                                    null,
                                    null,
                                    null,
                                    null,
                                    content
                            ))
                            .getObjectOrThrow();

            /*
             * IMPORTANT:
             *
             * message.id is TDLib's temporary outgoing message ID.
             *
             * The temporary file MUST NOT be deleted here.
             * TDLib can still need it after SendMessage returns.
             *
             * The file is deleted by TelegramMessageSendListener
             * after UpdateMessageSendSucceeded or
             * UpdateMessageSendFailed.
             */
            return new ConnectorSendResult(
                    String.valueOf(message.id)
            );

        } catch (IOException e) {
            deleteTemporaryFile(temporaryFile);

            throw new IllegalStateException(
                    "Failed to prepare Telegram attachment",
                    e
            );
        } catch (RuntimeException e) {
            deleteTemporaryFile(temporaryFile);

            throw e;
        }
    }

    private Path createTemporaryFile(
            UUID channelAccountId,
            UUID messageId,
            String fileName
    ) throws IOException {

        String suffix =
                getFileSuffix(fileName);

        return Files.createTempFile(
                "clientbus-telegram-"
                        + channelAccountId
                        + "-"
                        + messageId
                        + "-",
                suffix
        );
    }

    private TdApi.InputMessageContent createImageContent(
            TdApi.InputFileLocal inputFile,
            ChannelSendRequest request
    ) {
        TdApi.InputPhoto inputPhoto =
                new TdApi.InputPhoto(
                        inputFile,
                        null,
                        null,
                        new int[0],
                        0,
                        0
                );

        return new TdApi.InputMessagePhoto(
                inputPhoto,
                toFormattedText(request.content()),
                false,
                null,
                false
        );
    }

    private TdApi.InputMessageContent createAudioContent(
            TdApi.InputFileLocal inputFile,
            ChannelSendRequest request
    ) {
        TdApi.InputAudio inputAudio =
                new TdApi.InputAudio(
                        inputFile,
                        null,
                        0,
                        "",
                        ""
                );

        return new TdApi.InputMessageAudio(
                inputAudio,
                toFormattedText(request.content())
        );
    }

    private TdApi.FormattedText toFormattedText(String text) {
        return new TdApi.FormattedText(
                text == null ? "" : text,
                new TdApi.TextEntity[0]
        );
    }

    private ChannelAttachment getSingleAttachment(
            ChannelSendRequest request,
            MessageAttachmentType expectedType
    ) {
        return request.attachments().getFirst();
    }

    private long parseChatId(String recipientExternalId) {
        try {
            return Long.parseLong(recipientExternalId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Telegram recipientExternalId must be a numeric chat ID: "
                            + recipientExternalId,
                    e
            );
        }
    }

    private String getFileSuffix(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return ".tmp";
        }

        int dotIndex =
                fileName.lastIndexOf('.');

        if (dotIndex < 0
                || dotIndex == fileName.length() - 1) {
            return ".tmp";
        }

        String suffix =
                fileName.substring(dotIndex);

        if (suffix.length() > 16
                || !suffix.matches("\\.[A-Za-z0-9]+")) {
            return ".tmp";
        }

        return suffix;
    }

    private void deleteTemporaryFile(
            Path temporaryFile
    ) {
        if (temporaryFile == null) {
            return;
        }

        try {
            Files.deleteIfExists(
                    temporaryFile
            );
        } catch (IOException ignored) {
            // Cleanup failure must not trigger another send attempt.
        }
    }

    private void validateRequest(
            ChannelSendRequest request
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "ChannelSendRequest must not be null"
            );
        }

        if (request.channelAccountId() == null) {
            throw new IllegalArgumentException(
                    "channelAccountId must not be null"
            );
        }

        if (request.messageId() == null) {
            throw new IllegalArgumentException(
                    "messageId must not be null"
            );
        }

        if (request.recipientExternalId() == null
                || request.recipientExternalId().isBlank()) {
            throw new IllegalArgumentException(
                    "recipientExternalId must not be blank"
            );
        }

        if (request.type() == null) {
            throw new IllegalArgumentException(
                    "message type must not be null"
            );
        }

        if (request.type() == MessageType.TEXT) {

            if (!request.attachments().isEmpty()) {
                throw new IllegalArgumentException(
                        "Telegram TEXT message must not contain attachments"
                );
            }

            if (request.content() == null
                    || request.content().isBlank()) {
                throw new IllegalArgumentException(
                        "Telegram text message content must not be blank"
                );
            }

            return;
        }

        if (request.type() == MessageType.IMAGE) {
            validateSingleAttachment(
                    request,
                    MessageAttachmentType.IMAGE
            );
            return;
        }

        if (request.type() == MessageType.AUDIO) {
            validateSingleAttachment(
                    request,
                    MessageAttachmentType.AUDIO
            );
            return;
        }

        throw new IllegalArgumentException(
                "Unsupported Telegram outbound message type: "
                        + request.type()
        );
    }

    private void validateSingleAttachment(
            ChannelSendRequest request,
            MessageAttachmentType expectedType
    ) {
        List<ChannelAttachment> attachments =
                request.attachments();

        if (attachments.size() != 1) {
            throw new IllegalArgumentException(
                    "Telegram " + request.type()
                            + " message must contain exactly one attachment"
            );
        }

        ChannelAttachment attachment =
                attachments.getFirst();

        if (attachment == null) {
            throw new IllegalArgumentException(
                    "Telegram attachment must not be null"
            );
        }

        if (attachment.type() != expectedType) {
            throw new IllegalArgumentException(
                    "Expected Telegram attachment type "
                            + expectedType
                            + ", but got "
                            + attachment.type()
            );
        }

        if (attachment.content() == null) {
            throw new IllegalArgumentException(
                    "Telegram attachment content must not be null"
            );
        }
    }

    @FunctionalInterface
    private interface TelegramContentFactory {

        TdApi.InputMessageContent create(
                TdApi.InputFileLocal inputFile,
                ChannelSendRequest request
        );
    }
}