package kit.penny.clientbus.server.connector.telegram;

import kit.penny.clientbus.common.dto.conversation.PlatformConversationRequest;
import kit.penny.clientbus.common.dto.message.PlatformMessageRequest;
import kit.penny.clientbus.common.enums.ChannelType;
import kit.penny.clientbus.common.enums.MessageAttachmentType;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.connector.ConnectorSendResult;
import kit.penny.clientbus.server.connector.IChannelConnector;
import kit.penny.clientbus.server.connector.command.MarkMessagesReadCommand;
import kit.penny.clientbus.server.connector.command.SendMessageCommand;
import kit.penny.clientbus.server.connector.command.SyncAccountCommand;
import kit.penny.clientbus.server.connector.command.SyncConversationHistoryCommand;
import kit.penny.clientbus.server.connector.command.SyncRecentChatsCommand;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientContext;
import kit.penny.clientbus.server.connector.telegram.client.TelegramClientManager;
import kit.penny.clientbus.server.connector.telegram.client.TelegramInboundMessageProcessor;
import kit.penny.clientbus.server.service.ChannelAttachment;
import kit.penny.tdlib.client.TelegramClient;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class TelegramChannelConnector
        implements IChannelConnector {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TelegramChannelConnector.class
            );

    private static final int RECENT_CHATS_LIMIT = 100;

    private static final int HISTORY_DEFAULT_LIMIT = 50;

    private static final int HISTORY_MAX_LIMIT = 100;

    private final TelegramClientManager telegramClientManager;

    private final TelegramConversationMapper telegramConversationMapper;

    public TelegramChannelConnector(
            TelegramClientManager telegramClientManager,
            TelegramConversationMapper telegramConversationMapper
    ) {
        this.telegramClientManager =
                telegramClientManager;

        this.telegramConversationMapper =
                telegramConversationMapper;

    }

    @Override
    public boolean supports(
            ChannelType channelType
    ) {
        return channelType == ChannelType.TELEGRAM;
    }

    @Override
    public ConnectorSendResult handle(
            SendMessageCommand command
    ) {
        validateCommand(command);

        long chatId =
                parseChatId(
                        command.recipientExternalId()
                );

        TelegramClientContext context =
                telegramClientManager.require(
                        command.channelAccountId()
                );

        TelegramClient telegramClient =
                context.telegramClient();

        return switch (command.type()) {

            case TEXT -> sendText(
                    telegramClient,
                    chatId,
                    command
            );

            case IMAGE -> sendImage(
                    telegramClient,
                    chatId,
                    command
            );

            case AUDIO -> sendAudio(
                    telegramClient,
                    chatId,
                    command
            );

            default -> throw new IllegalArgumentException(
                    "Unsupported Telegram outbound message type: "
                            + command.type()
            );
        };
    }

    @Override
    public void handle(
            MarkMessagesReadCommand command
    ) {
        validateReadCommand(command);

        long chatId =
                parseChatId(
                        command.recipientExternalId()
                );

        long messageId =
                parseMessageId(
                        command.lastReadExternalId()
                );

        TelegramClientContext context =
                telegramClientManager.require(
                        command.channelAccountId()
                );

        context.telegramClient()
                .send(
                        new TdApi.ViewMessages(
                                chatId,
                                new long[]{messageId},
                                null,
                                true
                        )
                )
                .getObjectOrThrow();
    }

    @Override
    public List<PlatformConversationRequest> handle(
            SyncRecentChatsCommand command
    ) {
        validateSyncRecentChatsCommand(command);

        TelegramClientContext context =
                telegramClientManager.require(
                        command.channelAccountId()
                );

        TelegramClient telegramClient =
                context.telegramClient();

        TdApi.Chats chats =
                telegramClient
                        .send(
                                new TdApi.GetChats(
                                        new TdApi.ChatListMain(),
                                        RECENT_CHATS_LIMIT
                                )
                        )
                        .getObjectOrThrow();

        if (chats == null
                || chats.chatIds == null
                || chats.chatIds.length == 0) {

            log.debug(
                    "Telegram recent chats sync returned no chats: " +
                            "channelAccountId={}",
                    command.channelAccountId()
            );

            return List.of();
        }

        List<PlatformConversationRequest> conversations =
                new ArrayList<>();

        for (long chatId : chats.chatIds) {

            try {
                PlatformConversationRequest conversation =
                        syncChat(
                                telegramClient,
                                command.channelAccountId(),
                                chatId
                        );

                if (conversation != null) {
                    conversations.add(conversation);
                }

            } catch (RuntimeException e) {
                log.warn(
                        "Failed to synchronize Telegram chat: " +
                                "channelAccountId={}, chatId={}",
                        command.channelAccountId(),
                        chatId,
                        e
                );
            }
        }

        log.info(
                "Telegram recent chats synchronization completed: " +
                        "channelAccountId={}, fetchedChatCount={}, " +
                        "conversationCount={}",
                command.channelAccountId(),
                chats.chatIds.length,
                conversations.size()
        );

        return List.copyOf(conversations);
    }

    @Override
    public List<PlatformMessageRequest> handle(
            SyncConversationHistoryCommand command
    ) {
        validateSyncConversationHistoryCommand(command);

        TelegramClientContext context =
                telegramClientManager.require(
                        command.channelAccountId()
                );

        TelegramClient telegramClient =
                context.telegramClient();

        long chatId =
                parseChatId(
                        command.conversationExternalId()
                );

        int limit =
                normalizeHistoryLimit(
                        command.limit()
                );

        long beforeMessageId =
                command.beforeExternalId() == null
                        || command.beforeExternalId().isBlank()
                        ? 0
                        : parseMessageId(
                        command.beforeExternalId()
                );

        TdApi.Messages messages =
                telegramClient
                        .send(
                                new TdApi.GetChatHistory(
                                        chatId,
                                        beforeMessageId,
                                        0,
                                        limit,
                                        false
                                )
                        )
                        .getObjectOrThrow();

        if (messages == null
                || messages.messages == null
                || messages.messages.length == 0) {

            log.debug(
                    "Telegram conversation history returned no messages: " +
                            "channelAccountId={}, chatId={}, beforeMessageId={}, limit={}",
                    command.channelAccountId(),
                    chatId,
                    beforeMessageId,
                    limit
            );

            return List.of();
        }

        List<PlatformMessageRequest> result =
                new ArrayList<>(
                        messages.messages.length
                );

        TelegramInboundMessageProcessor telegramInboundMessageProcessor = context.applicationContext()
                .getBean(TelegramInboundMessageProcessor.class);

        for (TdApi.Message message : messages.messages) {

            if (message == null) {
                continue;
            }

            /*
             * TDLib may include the cursor message itself when
             * fromMessageId is specified. It is a cursor, not part
             * of the requested "before" page.
             */
            if (beforeMessageId != 0
                    && message.id == beforeMessageId) {
                continue;
            }

            try {
                PlatformMessageRequest request =
                        telegramInboundMessageProcessor
                                .process(
                                        command.channelAccountId(),
                                        message
                                )
                                .join();

                if (request != null) {
                    result.add(request);
                }

            } catch (RuntimeException e) {
                log.warn(
                        "Failed to process Telegram history message: " +
                                "channelAccountId={}, chatId={}, messageId={}",
                        command.channelAccountId(),
                        chatId,
                        message.id,
                        e
                );
            }
        }

        /*
         * TDLib returns chat history newest -> oldest.
         *
         * ClientBus history consumers must receive the page in
         * chronological order.
         */
        result.sort(
                java.util.Comparator.comparing(
                        PlatformMessageRequest::sentAt,
                        java.util.Comparator.nullsLast(
                                java.util.Comparator.naturalOrder()
                        )
                )
        );

        log.info(
                "Telegram conversation history synchronization completed: " +
                        "channelAccountId={}, chatId={}, beforeMessageId={}, " +
                        "requestedLimit={}, fetchedMessages={}, processedMessages={}",
                command.channelAccountId(),
                chatId,
                beforeMessageId,
                limit,
                messages.messages.length,
                result.size()
        );

        return List.copyOf(result);
    }

    private PlatformConversationRequest syncChat(
            TelegramClient telegramClient,
            UUID channelAccountId,
            long chatId
    ) {
        TdApi.Chat chat =
                telegramClient
                        .send(
                                new TdApi.GetChat(chatId)
                        )
                        .getObjectOrThrow();

        if (chat == null) {
            log.warn(
                    "Telegram chat response is empty: " +
                            "channelAccountId={}, chatId={}",
                    channelAccountId,
                    chatId
            );

            return null;
        }

        if (!(chat.type
                instanceof TdApi.ChatTypePrivate privateChat)) {

            log.debug(
                    "Skipping non-private Telegram chat: " +
                            "channelAccountId={}, chatId={}, type={}",
                    channelAccountId,
                    chatId,
                    chat.type == null
                            ? "null"
                            : chat.type
                            .getClass()
                            .getSimpleName()
            );

            return null;
        }

        TdApi.User user =
                telegramClient
                        .send(
                                new TdApi.GetUser(
                                        privateChat.userId
                                )
                        )
                        .getObjectOrThrow();

        if (user == null) {
            log.warn(
                    "Telegram user response is empty: " +
                            "channelAccountId={}, userId={}, chatId={}",
                    channelAccountId,
                    privateChat.userId,
                    chatId
            );

            return null;
        }

        return telegramConversationMapper.map(
                channelAccountId,
                chat,
                user
        );
    }

    @Override
    public void handle(
            SyncAccountCommand command
    ) {
        throw new UnsupportedOperationException(
                "Account synchronization is not implemented yet"
        );
    }

    private int normalizeHistoryLimit(
            int limit
    ) {
        if (limit <= 0) {
            return HISTORY_DEFAULT_LIMIT;
        }

        return Math.min(
                limit,
                HISTORY_MAX_LIMIT
        );
    }

    private void validateSyncRecentChatsCommand(
            SyncRecentChatsCommand command
    ) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "SyncRecentChatsCommand must not be null"
            );
        }

        if (command.channelAccountId() == null) {
            throw new IllegalArgumentException(
                    "channelAccountId must not be null"
            );
        }
    }

    private void validateSyncConversationHistoryCommand(
            SyncConversationHistoryCommand command
    ) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "SyncConversationHistoryCommand must not be null"
            );
        }

        if (command.channelAccountId() == null) {
            throw new IllegalArgumentException(
                    "channelAccountId must not be null"
            );
        }

        if (command.conversationExternalId() == null
                || command.conversationExternalId().isBlank()) {
            throw new IllegalArgumentException(
                    "conversationExternalId must not be blank"
            );
        }

        if (command.beforeExternalId() != null
                && command.beforeExternalId().isBlank()) {
            throw new IllegalArgumentException(
                    "beforeExternalId must not be blank"
            );
        }
    }

    private void validateReadCommand(
            MarkMessagesReadCommand command
    ) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "MarkMessagesReadCommand must not be null"
            );
        }

        if (command.channelAccountId() == null) {
            throw new IllegalArgumentException(
                    "channelAccountId must not be null"
            );
        }

        if (command.recipientExternalId() == null
                || command.recipientExternalId().isBlank()) {
            throw new IllegalArgumentException(
                    "recipientExternalId must not be blank"
            );
        }

        if (command.lastReadExternalId() == null
                || command.lastReadExternalId().isBlank()) {
            throw new IllegalArgumentException(
                    "lastReadExternalId must not be blank"
            );
        }
    }

    private ConnectorSendResult sendText(
            TelegramClient telegramClient,
            long chatId,
            SendMessageCommand command
    ) {
        TdApi.InputMessageText content =
                new TdApi.InputMessageText(
                        new TdApi.FormattedText(
                                command.content(),
                                new TdApi.TextEntity[0]
                        ),
                        null,
                        false
                );

        TdApi.Message message =
                telegramClient
                        .send(
                                new TdApi.SendMessage(
                                        chatId,
                                        null,
                                        null,
                                        null,
                                        null,
                                        content
                                )
                        )
                        .getObjectOrThrow();

        return new ConnectorSendResult(
                String.valueOf(message.id)
        );
    }

    private ConnectorSendResult sendImage(
            TelegramClient telegramClient,
            long chatId,
            SendMessageCommand command
    ) {
        ChannelAttachment attachment =
                getSingleAttachment(
                        command,
                        MessageAttachmentType.IMAGE
                );

        return sendWithTemporaryFile(
                telegramClient,
                chatId,
                command,
                attachment,
                this::createImageContent
        );
    }

    private ConnectorSendResult sendAudio(
            TelegramClient telegramClient,
            long chatId,
            SendMessageCommand command
    ) {
        ChannelAttachment attachment =
                getSingleAttachment(
                        command,
                        MessageAttachmentType.AUDIO
                );

        return sendWithTemporaryFile(
                telegramClient,
                chatId,
                command,
                attachment,
                this::createAudioContent
        );
    }

    private ConnectorSendResult sendWithTemporaryFile(
            TelegramClient telegramClient,
            long chatId,
            SendMessageCommand command,
            ChannelAttachment attachment,
            TelegramContentFactory contentFactory
    ) {
        Path temporaryFile = null;

        try (InputStream inputStream =
                     attachment.content()) {

            if (inputStream == null) {
                throw new IllegalArgumentException(
                        "Telegram attachment content must not be null"
                );
            }

            temporaryFile =
                    createTemporaryFile(
                            command.channelAccountId(),
                            command.messageId(),
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
                            command,
                            attachment.fileName()
                    );

            TdApi.Message message =
                    telegramClient
                            .send(
                                    new TdApi.SendMessage(
                                            chatId,
                                            null,
                                            null,
                                            null,
                                            null,
                                            content
                                    )
                            )
                            .getObjectOrThrow();

            return new ConnectorSendResult(
                    String.valueOf(message.id)
            );

        } catch (IOException e) {

            deleteTemporaryFile(
                    temporaryFile
            );

            throw new IllegalStateException(
                    "Failed to prepare Telegram attachment",
                    e
            );

        } catch (RuntimeException e) {

            deleteTemporaryFile(
                    temporaryFile
            );

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
            SendMessageCommand command,
            String fileName
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
                toFormattedText(command.content()),
                false,
                null,
                false
        );
    }

    private TdApi.InputMessageContent createAudioContent(
            TdApi.InputFileLocal inputFile,
            SendMessageCommand command,
            String fileName
    ) {
        TdApi.InputAudio inputAudio =
                new TdApi.InputAudio(
                        inputFile,
                        null,
                        0,
                        fileName,
                        ""
                );

        return new TdApi.InputMessageAudio(
                inputAudio,
                toFormattedText(command.content())
        );
    }

    private TdApi.FormattedText toFormattedText(
            String text
    ) {
        return new TdApi.FormattedText(
                text == null ? "" : text,
                new TdApi.TextEntity[0]
        );
    }

    private ChannelAttachment getSingleAttachment(
            SendMessageCommand command,
            MessageAttachmentType expectedType
    ) {
        return command.attachments().getFirst();
    }

    private long parseChatId(
            String recipientExternalId
    ) {
        try {
            return Long.parseLong(
                    recipientExternalId
            );
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Telegram recipientExternalId must be a numeric chat ID: "
                            + recipientExternalId,
                    e
            );
        }
    }

    private long parseMessageId(
            String externalId
    ) {
        try {
            return Long.parseLong(
                    externalId
            );
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Telegram message externalId must be numeric: "
                            + externalId,
                    e
            );
        }
    }

    private String getFileSuffix(
            String fileName
    ) {
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
                || !suffix.matches(
                "\\.[A-Za-z0-9]+"
        )) {
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

    private void validateCommand(
            SendMessageCommand command
    ) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "SendMessageCommand must not be null"
            );
        }

        if (command.channelAccountId() == null) {
            throw new IllegalArgumentException(
                    "channelAccountId must not be null"
            );
        }

        if (command.messageId() == null) {
            throw new IllegalArgumentException(
                    "messageId must not be null"
            );
        }

        if (command.recipientExternalId() == null
                || command.recipientExternalId().isBlank()) {
            throw new IllegalArgumentException(
                    "recipientExternalId must not be blank"
            );
        }

        if (command.type() == null) {
            throw new IllegalArgumentException(
                    "message type must not be null"
            );
        }

        if (command.type() == MessageType.TEXT) {

            if (!command.attachments().isEmpty()) {
                throw new IllegalArgumentException(
                        "Telegram TEXT message must not contain attachments"
                );
            }

            if (command.content() == null
                    || command.content().isBlank()) {
                throw new IllegalArgumentException(
                        "Telegram text message content must not be blank"
                );
            }

            return;
        }

        if (command.type() == MessageType.IMAGE) {

            validateSingleAttachment(
                    command,
                    MessageAttachmentType.IMAGE
            );

            return;
        }

        if (command.type() == MessageType.AUDIO) {

            validateSingleAttachment(
                    command,
                    MessageAttachmentType.AUDIO
            );

            return;
        }

        throw new IllegalArgumentException(
                "Unsupported Telegram outbound message type: "
                        + command.type()
        );
    }

    private void validateSingleAttachment(
            SendMessageCommand command,
            MessageAttachmentType expectedType
    ) {
        List<ChannelAttachment> attachments =
                command.attachments();

        if (attachments.size() != 1) {
            throw new IllegalArgumentException(
                    "Telegram " + command.type()
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
                SendMessageCommand command,
                String fileName
        );
    }
}
