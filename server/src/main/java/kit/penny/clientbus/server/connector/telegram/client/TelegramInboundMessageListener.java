package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.common.dto.message.InboundMessageRequest;
import kit.penny.clientbus.common.enums.MessageType;
import kit.penny.clientbus.server.service.MessageProcessingService;
import kit.penny.tdlib.client.TelegramClient;
import kit.penny.tdlib.query.TdlibResponse;
import kit.penny.tdlib.updates.ITdlibUpdateListener;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class TelegramInboundMessageListener
        implements ITdlibUpdateListener<TdApi.UpdateNewMessage> {

    private static final Logger log =
            LoggerFactory.getLogger(TelegramInboundMessageListener.class);

    private final UUID channelAccountId;
    private final ObjectProvider<TelegramClient>  telegramClientProvider;
    private final MessageProcessingService messageProcessingService;

    public TelegramInboundMessageListener(
            UUID channelAccountId,
            ObjectProvider<TelegramClient> telegramClientProvider,
            MessageProcessingService messageProcessingService
    ) {
        this.channelAccountId = channelAccountId;
        this.telegramClientProvider = telegramClientProvider;
        this.messageProcessingService = messageProcessingService;
    }

    @Override
    public void handleNotification(
            TdApi.UpdateNewMessage notification
    ) {
        if (notification == null || notification.message == null) {
            return;
        }

        TdApi.Message message = notification.message;

        if (message.isOutgoing) {
            log.debug(
                    "Ignoring outgoing Telegram message: channelAccountId={}, messageId={}",
                    channelAccountId,
                    message.id
            );
            return;
        }

        if (!(message.senderId instanceof TdApi.MessageSenderUser sender)) {
            log.debug(
                    "Ignoring Telegram message with unsupported sender: channelAccountId={}, messageId={}, senderType={}",
                    channelAccountId,
                    message.id,
                    message.senderId == null
                            ? "null"
                            : message.senderId.getClass().getSimpleName()
            );
            return;
        }

        if (!(message.content instanceof TdApi.MessageText messageText)) {
            log.debug(
                    "Ignoring non-text Telegram message: channelAccountId={}, messageId={}, contentType={}",
                    channelAccountId,
                    message.id,
                    message.content == null
                            ? "null"
                            : message.content.getClass().getSimpleName()
            );
            return;
        }

        telegramClientProvider
                .getObject()
                .sendAsync(new TdApi.GetChat(message.chatId))
                .thenAccept(response ->
                        handleChatResponse(
                                message,
                                sender,
                                messageText,
                                response
                        )
                );
    }

    private void handleChatResponse(
            TdApi.Message message,
            TdApi.MessageSenderUser sender,
            TdApi.MessageText messageText,
            TdlibResponse<TdApi.Chat> response
    ) {
        if (response.getError().isPresent()) {
            log.warn(
                    "Failed to load Telegram chat: channelAccountId={}, chatId={}, messageId={}, error={}",
                    channelAccountId,
                    message.chatId,
                    message.id,
                    response.getError().get().message
            );
            return;
        }

        TdApi.Chat chat = response.getObject().orElse(null);

        if (chat == null) {
            log.warn(
                    "Telegram chat response is empty: channelAccountId={}, chatId={}, messageId={}",
                    channelAccountId,
                    message.chatId,
                    message.id
            );
            return;
        }

        if (!(chat.type instanceof TdApi.ChatTypePrivate)) {
            log.debug(
                    "Ignoring Telegram message from non-private chat: channelAccountId={}, chatId={}, messageId={}, chatType={}",
                    channelAccountId,
                    message.chatId,
                    message.id,
                    chat.type == null
                            ? "null"
                            : chat.type.getClass().getSimpleName()
            );
            return;
        }

        String content =
                messageText.text == null
                        ? null
                        : messageText.text.text;

        InboundMessageRequest request =
                new InboundMessageRequest(
                        channelAccountId,
                        Long.toString(sender.userId),
                        null,
                        null,
                        null,
                        Long.toString(message.id),
                        MessageType.TEXT,
                        content,
                        null,
                        Instant.ofEpochSecond(message.date)
                );

        try {
            messageProcessingService.processInbound(
                    request,
                    List.of()
            );

            log.debug(
                    "Telegram inbound message processed: channelAccountId={}, chatId={}, messageId={}, clientExternalId={}",
                    channelAccountId,
                    message.chatId,
                    message.id,
                    sender.userId
            );

        } catch (RuntimeException e) {
            log.error(
                    "Failed to process Telegram inbound message: channelAccountId={}, chatId={}, messageId={}",
                    channelAccountId,
                    message.chatId,
                    message.id,
                    e
            );
        }
    }

    @Override
    public Class<TdApi.UpdateNewMessage> notificationType() {
        return TdApi.UpdateNewMessage.class;
    }
}