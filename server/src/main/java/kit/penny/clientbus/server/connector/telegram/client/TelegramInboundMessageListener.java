package kit.penny.clientbus.server.connector.telegram.client;

import kit.penny.clientbus.server.kafka.producer.IPlatformMessagePublisher;
import kit.penny.tdlib.updates.ITdlibUpdateListener;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class TelegramInboundMessageListener
        implements ITdlibUpdateListener<TdApi.UpdateNewMessage> {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TelegramInboundMessageListener.class
            );

    private final UUID channelAccountId;

    private final TelegramInboundMessageProcessor
            messageProcessor;

    private final IPlatformMessagePublisher platformMessagePublisher;

    public TelegramInboundMessageListener(
            UUID channelAccountId,
            TelegramInboundMessageProcessor messageProcessor,
            IPlatformMessagePublisher platformMessagePublisher
    ) {
        this.channelAccountId = channelAccountId;
        this.messageProcessor = messageProcessor;
        this.platformMessagePublisher = platformMessagePublisher;
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
         * It is handled by TelegramMessageSendListener through
         * UpdateMessageSendSucceeded / UpdateMessageSendFailed.
         *
         * Outgoing message without a sending state was created
         * outside the current ClientBus send flow, for example
         * from another Telegram device, and must be processed
         * as a normal platform message.
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

        /*
         * History/realtime processing currently supports messages
         * sent by Telegram users only.
         *
         * Group/channel/other sender types are intentionally ignored
         * at this stage.
         */
        if (!(message.senderId
                instanceof TdApi.MessageSenderUser)) {

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

        messageProcessor
                .process(
                        channelAccountId,
                        message
                )
                .thenAccept(platformMessage -> {
                    if (platformMessage == null) {
                        return;
                    }

                    platformMessagePublisher.publish(platformMessage);
                })
                .exceptionally(error -> {

                    log.error(
                            "Failed to process Telegram message: " +
                                    "channelAccountId={}, chatId={}, messageId={}",
                            channelAccountId,
                            message.chatId,
                            message.id,
                            error
                    );

                    return null;
                });
    }

    @Override
    public Class<TdApi.UpdateNewMessage> notificationType() {
        return TdApi.UpdateNewMessage.class;
    }
}